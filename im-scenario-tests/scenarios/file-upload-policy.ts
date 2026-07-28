import { assertOk } from "../src/assertions.js";
import { loadScenarioConfig } from "../src/config.js";
import { ScenarioHttpError } from "../src/http-client.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";

interface UploadPolicy {
  fileId: string;
  uploadUrl: string;
  method: "POST";
  formFields: Record<string, string>;
  fileField: string;
}

interface UploadCompleteResult {
  fileId: string;
  fileSize: number;
}

interface DownloadSignResult {
  fileId: string;
  fileUrl: string;
}

interface MinioPostResult {
  status: number;
  body: string;
}

const FILE_SIZE = 3;
const MISSING_RESOURCE_MESSAGE = "资源不存在或已被删除";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const alice = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Upload Policy ${suffix}`,
});

try {
  reporter.step("registering an authenticated upload-policy client");
  await alice.register();
  await alice.connectAndLogin();

  reporter.step("proving an exact-size object succeeds through a fresh MinIO POST policy");
  const exactPolicy = await signUpload(`exact-${suffix}.txt`);
  const exactUpload = await postToMinio(exactPolicy, new Uint8Array(FILE_SIZE));
  assertOk(exactUpload.status >= 200 && exactUpload.status < 300,
    `exact-size MinIO POST failed: HTTP ${exactUpload.status}: ${exactUpload.body.slice(0, 200)}`);
  const completed = await alice.http.post<UploadCompleteResult>("/api/file/upload/complete", {
    fileId: exactPolicy.fileId,
  });
  assertOk(completed.fileId === exactPolicy.fileId && completed.fileSize === FILE_SIZE,
    `exact-size upload did not complete with expected metadata: ${JSON.stringify(completed)}`);

  reporter.step("proving the completed exact-size object is downloadable");
  const exactDownload = await alice.http.post<DownloadSignResult>("/api/file/download/sign", {
    fileId: exactPolicy.fileId,
  });
  assertOk(exactDownload.fileId === exactPolicy.fileId && Boolean(exactDownload.fileUrl),
    `exact-size upload did not produce a download URL: ${JSON.stringify(exactDownload)}`);

  reporter.step("requesting a separate policy for the oversized-object rejection");
  const oversizedPolicy = await signUpload(`oversized-${suffix}.txt`);

  reporter.step("posting a larger object through the public MinIO policy URL");
  const oversizedUpload = await postToMinio(oversizedPolicy, new Uint8Array(FILE_SIZE + 1));
  assertOk(oversizedUpload.status === 400,
    `oversized MinIO POST returned HTTP ${oversizedUpload.status}, expected 400: ${oversizedUpload.body.slice(0, 200)}`);
  assertOk(xmlElement(oversizedUpload.body, "Code") === "EntityTooLarge",
    `oversized MinIO POST did not return EntityTooLarge: ${oversizedUpload.body.slice(0, 400)}`);
  assertOk(xmlElement(oversizedUpload.body, "Message") ===
    "Your proposed upload exceeds the maximum allowed object size.",
  `oversized MinIO POST returned an unexpected policy error body: ${oversizedUpload.body.slice(0, 400)}`);

  reporter.step("checking rejected upload leaves no downloadable metadata");
  const completionError = await expectRejection(() => alice.http.post("/api/file/upload/complete", {
    fileId: oversizedPolicy.fileId,
  }));
  assertMissingMetadataError(completionError, "/api/file/upload/complete", "uploaded object not found");

  const downloadError = await expectRejection(() => alice.http.post("/api/file/download/sign", {
    fileId: oversizedPolicy.fileId,
  }));
  assertMissingMetadataError(downloadError, "/api/file/download/sign", "file not found");

  reporter.metric("exactFileId", exactPolicy.fileId);
  reporter.metric("rejectedFileId", oversizedPolicy.fileId);
  reporter.finish();
} finally {
  alice.close();
}

async function signUpload(fileName: string): Promise<UploadPolicy> {
  const signed = await alice.http.post<UploadPolicy>("/api/file/upload/sign", {
    fileName,
    fileSize: FILE_SIZE,
    mimeType: "text/plain",
  });
  assertOk(signed.fileId, "upload policy did not return fileId");
  assertOk(signed.method === "POST", `expected POST upload policy, got ${signed.method}`);
  assertOk(signed.uploadUrl, "upload policy did not return uploadUrl");
  assertOk(signed.fileField, "upload policy did not return fileField");
  assertOk(Object.keys(signed.formFields).length > 0, "upload policy did not return formFields");
  return signed;
}

async function postToMinio(policy: UploadPolicy, bytes: Uint8Array): Promise<MinioPostResult> {
  const form = new FormData();
  for (const [key, value] of Object.entries(policy.formFields)) {
    form.append(key, value);
  }
  form.append(policy.fileField, new Blob([toArrayBuffer(bytes)], { type: "text/plain" }), "a.txt");

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.requestTimeoutMs);
  try {
    const response = await fetch(policy.uploadUrl, {
      method: policy.method,
      body: form,
      signal: controller.signal,
    });
    const body = await response.text();
    return { status: response.status, body };
  } finally {
    clearTimeout(timeout);
  }
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(copy).set(bytes);
  return copy;
}

async function expectRejection(operation: () => Promise<unknown>): Promise<Error> {
  try {
    await operation();
  } catch (error) {
    return error instanceof Error ? error : new Error(String(error));
  }
  throw new Error("expected operation to reject");
}

function assertMissingMetadataError(error: Error, path: string, expectedDetail: string): void {
  assertOk(error instanceof ScenarioHttpError,
    `${path} did not return a parsed API error envelope: ${error.message}`);
  assertOk(error.httpStatus === 404 && error.code === 404,
    `${path} did not return business code 404: ${error.message}`);
  assertOk(error.path === path && error.msg === MISSING_RESOURCE_MESSAGE,
    `${path} did not return the stable missing-resource response: ${error.message}`);
  if (error.detail !== undefined) {
    assertOk(error.detail === expectedDetail,
      `${path} returned unexpected missing-resource detail: ${error.detail}`);
  }
}

function xmlElement(xml: string, name: string): string | undefined {
  const match = xml.match(new RegExp(`<${name}>([^<]+)</${name}>`));
  return match?.[1];
}

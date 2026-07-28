import { assertOk } from "../src/assertions.js";
import { loadScenarioConfig } from "../src/config.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";

interface UploadPolicy {
  fileId: string;
  uploadUrl: string;
  method: "POST";
  formFields: Record<string, string>;
  fileField: string;
}

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

  reporter.step("requesting an exact-size MinIO POST policy");
  const signed = await alice.http.post<UploadPolicy>("/api/file/upload/sign", {
    fileName: "a.txt",
    fileSize: 3,
    mimeType: "text/plain",
  });
  assertOk(signed.fileId, "upload policy did not return fileId");
  assertOk(signed.method === "POST", `expected POST upload policy, got ${signed.method}`);
  assertOk(signed.uploadUrl, "upload policy did not return uploadUrl");
  assertOk(signed.fileField, "upload policy did not return fileField");
  assertOk(Object.keys(signed.formFields).length > 0, "upload policy did not return formFields");

  reporter.step("posting a larger object through the public MinIO policy URL");
  const uploadError = await expectRejection(() => postToMinio(signed, new Uint8Array(4)));
  assertOk(/MinIO POST rejected: HTTP \d+/.test(uploadError.message),
    `larger direct object was not rejected by MinIO: ${uploadError.message}`);

  reporter.step("checking failed completion creates no downloadable metadata");
  const completionError = await expectRejection(() => alice.http.post("/api/file/upload/complete", {
    fileId: signed.fileId,
  }));
  assertNotFoundError(completionError, "upload completion");

  const downloadError = await expectRejection(() => alice.http.post("/api/file/download/sign", {
    fileId: signed.fileId,
  }));
  assertNotFoundError(downloadError, "download signing after rejected completion");

  reporter.metric("fileId", signed.fileId);
  reporter.finish();
} finally {
  alice.close();
}

async function postToMinio(policy: UploadPolicy, bytes: Uint8Array): Promise<void> {
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
    if (response.ok) {
      throw new Error(`MinIO accepted ${bytes.byteLength} bytes for a 3-byte policy`);
    }
    const body = await response.text();
    throw new Error(`MinIO POST rejected: HTTP ${response.status}: ${body.slice(0, 200)}`);
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

function assertNotFoundError(error: Error, operation: string): void {
  assertOk(/^(?:HTTP 404\b|API 404:)/.test(error.message),
    `${operation} did not reject as missing upload metadata: ${error.message}`);
}

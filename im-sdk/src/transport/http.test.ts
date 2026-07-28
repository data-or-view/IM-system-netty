import type { PostPolicyUploadResponse } from "./http.js";

const postPolicy: PostPolicyUploadResponse = {
  fileId: "file-1",
  uploadUrl: "https://minio.test/im-system",
  method: "POST",
  formFields: { key: "uploads/file-1.txt" },
  fileField: "file",
};

void postPolicy;

// @ts-expect-error POST policies deliberately do not expose legacy PUT headers.
const legacyPutShape: PostPolicyUploadResponse = { fileId: "file-1", uploadUrl: "https://minio.test/upload", headers: {} };
void legacyPutShape;

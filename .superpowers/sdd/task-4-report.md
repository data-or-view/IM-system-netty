# Task 4 Report: Storage-Bounded Direct Uploads

## Delivered

- Replaced public direct-object PUT uploads with exact-size MinIO POST policies.
  The API now exposes `PresignedPostPolicy` and `FileObjectStat`; signed upload
  responses contain `method=POST`, object-storage form fields, and `fileField`.
- MinIO POST policies constrain bucket, key, content type, and an exact
  `content-length-range`. The returned fields now explicitly include `key` and
  `Content-Type`, which MinIO's signing API does not add itself.
- Completion reads object metadata before persistence. Missing objects do not
  consume a session; size or content-type mismatches delete the object and
  session; metadata-write failures retain the session for a retry.
- Legacy proxy `file.upload` and all new multipart init/sign/part/complete
  paths return a POST-upload migration validation error. Existing multipart
  abort remains owner-bound.
- Netty HTTP aggregation is capped at 1 MiB. The SDK now uploads the returned
  fields and blob as a multipart POST form and rejects legacy multipart starts.
- `im.file.max-upload-bytes` is the primary limit. `im.minio.max-file-size`
  remains a deprecated compatibility fallback.

## TDD Evidence

### RED

Initial focused Java tests failed as intended because signing returned `PUT`
instead of `POST` and proxy multipart remained active. The initial SDK test
could not start because the worktree had no local `tsc`; no package was
installed. The existing repository SDK TypeScript binary was then used through
`PATH`.

### GREEN

```text
mvn -q -pl im-api,im-server -am \
  -Dtest=DirectFileTransferUseCaseTest,FileDirectTransferHandlerTest,FileMultipartHandlerTest,HttpRequestAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Passed: 13 `DirectFileTransferUseCaseTest`, 2 `FileDirectTransferHandlerTest`,
1 `FileMultipartHandlerTest`, and 10 `HttpRequestAdapterTest` tests.

```text
PATH=<repository im-sdk node_modules/.bin>:$PATH npm --prefix im-sdk test
```

Passed: 54 SDK tests.

`git diff --check` also passed.

## Integration Test

Added `MinioPostPolicyE2ETest`, which uses a real POST multipart request and
checks persisted object metadata when `IM_MINIO_ENDPOINT`,
`IM_MINIO_ACCESS_KEY`, `IM_MINIO_SECRET_KEY`, and `IM_MINIO_BUCKET` are set.
Port 9000 was reachable locally, but the default development credentials were
rejected by that MinIO instance (`InvalidAccessKeyId`). The E2E test therefore
skipped cleanly without explicit environment credentials:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 1
```

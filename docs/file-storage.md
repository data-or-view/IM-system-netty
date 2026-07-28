# 文件存储系统

文件对象存储在 MinIO，元数据持久化到 MySQL，上传会话存储在 Redis。生产上传只支持由 SDK 驱动的 MinIO POST policy 直传；IM HTTP 服务只接收小型、已认证的 JSON 控制请求，不代理文件二进制。

## 上传链路

```text
Client / im-sdk
  -> POST /api/file/upload/sign (JSON)
  <- fileId, uploadUrl, method=POST, formFields, fileField
  -> POST uploadUrl (multipart/form-data, directly to MinIO)
  -> POST /api/file/upload/complete (JSON)
  -> MinIO statObject verifies exact size and content type
  -> MySQL im_objects metadata is written
```

Redis 上传会话绑定 `fileId`、用户、bucket、object key、声明的字节数和 MIME type。完成请求会读取 MinIO 对象信息；对象不存在，或实际大小/MIME type 与签名会话不一致时，服务端拒绝完成、删除不匹配对象和上传会话，并且不写文件元数据。

## API 合约

所有文件控制请求都需要有效登录 token，并使用统一 `{code, msg, data}` 响应 envelope。

### 1. 获取 POST policy

```http
POST /api/file/upload/sign
Content-Type: application/json
Authorization: Bearer <token>

{
  "fileName": "report.pdf",
  "fileSize": 12345,
  "mimeType": "application/pdf"
}
```

成功响应的 `data`：

```json
{
  "fileId": "file-id",
  "objectKey": "uploads/file-id.pdf",
  "uploadUrl": "http://minio.example/im-system",
  "method": "POST",
  "formFields": {
    "key": "uploads/file-id.pdf",
    "policy": "...",
    "x-amz-algorithm": "...",
    "x-amz-credential": "...",
    "x-amz-date": "...",
    "x-amz-signature": "...",
    "Content-Type": "application/pdf"
  },
  "fileField": "file",
  "expiresIn": 900
}
```

客户端必须原样提交所有 `formFields`，最后以 `fileField` 指定的字段名追加文件，并对 `uploadUrl` 发起 `multipart/form-data` POST。不要手工设置 multipart boundary。

### 2. 完成上传

只有 MinIO POST 返回成功后才调用：

```http
POST /api/file/upload/complete
Content-Type: application/json
Authorization: Bearer <token>

{"fileId":"file-id"}
```

服务端验证对象后返回文件 URL、`fileId`、`objectKey`、文件名、MIME type 和大小。重复完成同一已成功上传的 `fileId` 是幂等读取；失败的大小/MIME 校验不会留下可下载元数据。

### 3. 获取下载签名

```http
POST /api/file/download/sign
Content-Type: application/json
Authorization: Bearer <token>

{"fileId":"file-id"}
```

只有文件所有者可获取下载签名。

## SDK 用法

```typescript
const result = await client.file.upload(
  file.name,
  file,
  file.type || "application/octet-stream",
);
```

`im-sdk` 会依次执行签名、MinIO POST 和完成请求。业务客户端不应自行拼接对象 key、签名字段或回退到 IM 服务代理上传。

## 限制与配置

| 配置 | 说明 |
|------|------|
| `im.file.max-upload-bytes` | 声明文件大小上限，默认 100 MiB。`im.minio.max-file-size` 仅为兼容旧配置的 fallback。 |
| `im.minio.endpoint` | MinIO API 地址，必须是客户端能够访问的地址。 |
| `im.minio.access-key` / `im.minio.secret-key` | 服务端签名凭据；非本地环境禁止使用开发默认值。 |
| `im.minio.bucket` | 对象 bucket，默认 `im-system`。 |
| `im.minio.presign-expire-seconds` | 上传和下载签名有效期，默认 900 秒。 |

允许扩展名用于保留 object key 后缀：`jpg`、`jpeg`、`png`、`gif`、`webp`、`bmp`、`mp4`、`mp3`、`wav`、`ogg`、`pdf`、`doc`、`docx`、`xls`、`xlsx`、`zip`、`txt`、`json`、`csv`。其它扩展名不会成为 object key 后缀，但上传仍由大小和 MIME policy 控制。

## 升级兼容性

服务端升级前必须先发布使用当前 `im-sdk` POST-policy 流程的客户端。以下旧流程不兼容并会被新服务端拒绝：

- 向 `/api/file/upload` 发送原始二进制或 base64。
- 使用预签名 `PUT` 直传。
- 调用 multipart init、part-sign、upload-part 或 complete。

发布顺序是“客户端 POST policy 支持 -> 确认旧客户端退出服务 -> 服务端升级”。不要在服务端保留旧上传 fallback。

## 验证

真实 MinIO 和后端启动后运行：

```bash
pnpm --dir im-scenario-tests scenario:file-upload-policy
```

场景声明 3 字节对象，实际通过返回的 MinIO URL POST 4 字节，验证 MinIO policy 拒绝；随后验证 `/api/file/upload/complete` 和 `/api/file/download/sign` 都返回业务错误，证明没有生成可下载元数据。

基础设施级 POST policy E2E 需要显式提供 `IM_MINIO_ENDPOINT`、`IM_MINIO_ACCESS_KEY`、`IM_MINIO_SECRET_KEY` 和 `IM_MINIO_BUCKET` 后单独运行相应 Maven E2E。

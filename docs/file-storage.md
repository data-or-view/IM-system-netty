# 文件存储系统

基于 MinIO 的自建对象存储，提供文件上传/下载/删除功能。

## 架构

```
Client (WebSocket/TCP)
    │ FILE_UPLOAD(100) {body=fileBytes}
    ▼
FileUploadHandler → MinioFileStorageService → MinIO API (port 9000)
    │
    ▼
FILE_UPLOAD_ACK(101) {fileUrl, fileId}
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MINIO_ENDPOINT` | `http://127.0.0.1:9000` | MinIO API 地址 |
| `MINIO_ACCESS_KEY` | `minioadmin` | 访问密钥 |
| `MINIO_SECRET_KEY` | `minioadmin` | 秘密密钥 |
| `MINIO_BUCKET` | `im-system` | 默认存储桶（仅前端使用） |

## 协议

### 上传文件

**请求** (FILE_UPLOAD = 100)
```
HEADERS:
  fileName  — 原始文件名（可选）
  mimeType  — MIME 类型（可选）

BODY: 文件二进制内容
```

**响应** (FILE_UPLOAD_ACK = 101)
```
HEADERS:
  status    — "OK"
  fileUrl   — 预签名 URL（有效期 7 天，可直接访问）
  fileId    — 服务端生成的 UUID（去横线）
  fileName  — 原始文件名
  mimeType  — MIME 类型
  fileSize  — 文件大小（字节）
```

### 限制

- 文件大小上限：10 MB
- 存储路径：`uploads/{fileId}.{ext}`
- 允许的扩展名：jpg, jpeg, png, gif, webp, bmp, mp4, mp3, wav, ogg, pdf, doc, docx, xls, xlsx, zip, txt, json, csv

## 前端用法

```typescript
import { imConnection } from '../protocol/connection';

// 上传文件，返回 URL
const fileUrl = await imConnection.uploadFile(fileInput.files[0]);

// 发送图片消息
imConnection.send({
  _op: String(CMD.SINGLE_CHAT),
  fromUserId: userId,
  toUserId: targetUserId,
  contentType: '2',  // 图片类型
  content: fileUrl,  // 文件 URL
});
```

## 部署检查

### 启动 MinIO
```bash
minio server /opt/minio/data --console-address :9001 --address :9000
```

### 验证
```bash
# mc 连接
mc alias set local http://127.0.0.1:9000 minioadmin minioadmin

# 检查 bucket
mc ls local/im-system/

# 上传测试
echo hello | mc pipe local/im-system/test.txt
mc cat local/im-system/test.txt
```

### 服务端日志确认
```
MinIO connected: endpoint=http://127.0.0.1:9000, buckets=1
FileStorage: MinIO (bucket=im-system, endpoint=http://127.0.0.1:9000)
```

## 依赖

- MinIO Java SDK: `io.minio:minio:8.5.17`
- shade 打包注意：需排除 `META-INF/*.SF`, `META-INF/*.DSA`, `META-INF/*.RSA`

package com.im.api;

/**
 * 文件存储接口（OSS 抽象）。
 *
 * 对应 OpenIM 的 third rpc service（对象存储 + 文件管理）。
 *
 * 典型场景：
 *   · 图片/文件/语音消息的上传
 *   · 头像/群头像的存储
 *   · 日志/归档文件的上传
 *
 * 对接的开源方案：
 *   ▸ MinIO（自部署，兼容 S3 API）
 *   ▸ OSS（阿里云）
 *   ▸ COS（腾讯云）
 *   ▸ S3（AWS）
 */
public interface IFileStorage {

    /**
     * 上传文件。
     *
     * @param bucket     存储桶名称
     * @param objectKey  对象 key（如 "images/2026/05/abc.jpg"）
     * @param data       文件字节数组
     * @param contentType MIME 类型
     * @return 可公开访问的 URL
     */
    String upload(String bucket, String objectKey, byte[] data, String contentType);

    /**
     * 生成上传凭证（客户端直传模式，避免服务端中转）。
     *
     * @param bucket     存储桶名称
     * @param objectKey  对象 key
     * @return 上传凭证（含临时密钥/签名 URL）
     */
    UploadCredential generateUploadCredential(String bucket, String objectKey);

    /**
     * 删除文件。
     */
    void delete(String bucket, String objectKey);

    /**
     * 获取文件访问 URL。
     *
     * @param bucket    存储桶名称
     * @param objectKey 对象 key
     * @param ttl       有效期（秒），0=永久
     * @return 访问 URL
     */
    String getFileUrl(String bucket, String objectKey, int ttl);

    /**
     * 上传凭证。
     */
    class UploadCredential {
        private final String uploadUrl;
        private final String accessKeyId;
        private final String accessKeySecret;
        private final String securityToken;
        private final long expiration;

        public UploadCredential(String uploadUrl, String accessKeyId,
                                String accessKeySecret, String securityToken,
                                long expiration) {
            this.uploadUrl = uploadUrl;
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.securityToken = securityToken;
            this.expiration = expiration;
        }

        public String getUploadUrl() { return uploadUrl; }
        public String getAccessKeyId() { return accessKeyId; }
        public String getAccessKeySecret() { return accessKeySecret; }
        public String getSecurityToken() { return securityToken; }
        public long getExpiration() { return expiration; }
    }
}

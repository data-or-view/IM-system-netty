package com.im.api;

/**
 * 文件存储服务接口。
 *
 * 支持文件上传/下载/删除/URL 获取，可通过 MinIO、S3、本地文件系统等实现。
 */
public interface IFileStorageService {

    /**
     * 上传文件。
     *
     * @param bucket   存储桶名称（如 {@code im-system}）
     * @param objectId 对象键（如 {@code images/2026/05/abc123.jpg}）
     * @param data     文件字节
     * @param mimeType MIME 类型（如 {@code image/jpeg}）
     * @return 可访问的文件 URL
     */
    String upload(String bucket, String objectId, byte[] data, String mimeType);

    /**
     * 下载文件。
     *
     * @param bucket   存储桶名称
     * @param objectId 对象键
     * @return 文件字节
     */
    byte[] download(String bucket, String objectId);

    /**
     * 删除文件。
     *
     * @param bucket   存储桶名称
     * @param objectId 对象键
     */
    void delete(String bucket, String objectId);

    /**
     * 生成文件的可访问 URL（公开或预签名）。
     *
     * @param bucket   存储桶名称
     * @param objectId 对象键
     * @return 文件 URL
     */
    String getUrl(String bucket, String objectId);

    /**
     * 判断对象是否存在。
     */
    boolean exists(String bucket, String objectId);

    // ── 分片上传 ──

    /**
     * 初始化分片上传。
     *
     * @param bucket   存储桶名称
     * @param objectId 对象键
     * @return uploadId
     */
    default String initiateMultipartUpload(String bucket, String objectId) {
        throw new UnsupportedOperationException("initiateMultipartUpload not implemented");
    }

    /**
     * 上传分片。
     *
     * @param bucket     存储桶名称
     * @param objectId   对象键
     * @param uploadId   上传 ID
     * @param partNumber 分片编号（1~10000）
     * @param data       分片数据
     * @return ETag
     */
    default String uploadPart(String bucket, String objectId, String uploadId,
                              int partNumber, byte[] data) {
        throw new UnsupportedOperationException("uploadPart not implemented");
    }

    /**
     * 完成分片上传。
     *
     * @param bucket   存储桶名称
     * @param objectId 对象键
     * @param uploadId 上传 ID
     * @param parts    分片列表（已按 partNumber 排序）
     */
    default void completeMultipartUpload(String bucket, String objectId, String uploadId,
                                         java.util.List<PartInfo> parts) {
        throw new UnsupportedOperationException("completeMultipartUpload not implemented");
    }

    /**
     * 中止分片上传。
     *
     * @param bucket   存储桶名称
     * @param objectId 对象键
     * @param uploadId 上传 ID
     */
    default void abortMultipartUpload(String bucket, String objectId, String uploadId) {
        throw new UnsupportedOperationException("abortMultipartUpload not implemented");
    }

    /**
     * 分片信息。
     */
    record PartInfo(int partNumber, String etag) {}
}

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
}

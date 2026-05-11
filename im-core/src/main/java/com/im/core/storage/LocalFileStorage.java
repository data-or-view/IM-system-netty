package com.im.core.storage;

import com.im.api.IFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地文件存储（占位 no-op）。
 *
 * 生产环境请换 MinioStorage / OssStorage / CosStorage / S3Storage。
 */
public class LocalFileStorage implements IFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    @Override
    public String upload(String bucket, String objectKey, byte[] data, String contentType) {
        log.debug("File upload skipped (local): bucket={}, key={}, type={}, size={}",
                bucket, objectKey, contentType, data != null ? data.length : 0);
        return "local://" + bucket + "/" + objectKey;
    }

    @Override
    public UploadCredential generateUploadCredential(String bucket, String objectKey) {
        return new UploadCredential("http://localhost:9000/" + bucket + "/" + objectKey,
                "", "", "", 0);
    }

    @Override
    public void delete(String bucket, String objectKey) {
        log.debug("File delete skipped (local): bucket={}, key={}", bucket, objectKey);
    }

    @Override
    public String getFileUrl(String bucket, String objectKey, int ttl) {
        return "local://" + bucket + "/" + objectKey;
    }
}

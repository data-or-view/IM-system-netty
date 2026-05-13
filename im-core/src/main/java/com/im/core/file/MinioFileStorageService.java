package com.im.core.file;

import com.im.api.IFileStorageService;
import io.minio.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 文件存储服务实现。
 *
 * <h3>环境变量</h3>
 * <ul>
 *   <li>{@code MINIO_ENDPOINT} — MinIO API 地址，默认 {@code http://127.0.0.1:9000}</li>
 *   <li>{@code MINIO_ACCESS_KEY} — 访问密钥，默认 {@code minioadmin}</li>
 *   <li>{@code MINIO_SECRET_KEY} — 秘密密钥，默认 {@code minioadmin}</li>
 *   <li>{@code MINIO_BUCKET} — 默认存储桶，默认 {@code im-system}</li>
 * </ul>
 */
public class MinioFileStorageService implements IFileStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorageService.class);

    private final MinioClient client;
    private final String endpoint;

    public MinioFileStorageService() {
        String ep = env("MINIO_ENDPOINT", "http://127.0.0.1:9000");
        String ak = env("MINIO_ACCESS_KEY", "minioadmin");
        String sk = env("MINIO_SECRET_KEY", "minioadmin");

        // 去除末尾斜杠
        if (ep.endsWith("/")) {
            ep = ep.substring(0, ep.length() - 1);
        }
        this.endpoint = ep;

        this.client = MinioClient.builder()
                .endpoint(ep)
                .credentials(ak, sk)
                .build();

        // 启动时检查连接（静默）
        try {
            client.listBuckets();
            log.info("MinIO connected: endpoint={}, buckets={}", ep, client.listBuckets().size());
        } catch (Exception e) {
            log.warn("MinIO connection check failed (will retry on first use): {}", e.getMessage());
        }
    }

    @Override
    public String upload(String bucket, String objectId, byte[] data, String mimeType) {
        try {
            ensureBucket(bucket);
            try (InputStream is = new ByteArrayInputStream(data)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectId)
                        .stream(is, data.length, -1)
                        .contentType(mimeType != null ? mimeType : "application/octet-stream")
                        .build());
            }
            String url = getUrl(bucket, objectId);
            log.debug("File uploaded: bucket={}, object={}, size={}, url={}", bucket, objectId, data.length, url);
            return url;
        } catch (Exception e) {
            throw new RuntimeException("MinIO upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] download(String bucket, String objectId) {
        try {
            try (InputStream is = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectId)
                    .build())) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            throw new RuntimeException("MinIO download failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String bucket, String objectId) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectId)
                    .build());
            log.debug("File deleted: bucket={}, object={}", bucket, objectId);
        } catch (Exception e) {
            throw new RuntimeException("MinIO delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String getUrl(String bucket, String objectId) {
        try {
            // 尝试生成预签名 URL（有效期 7 天）
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectId)
                    .expiry(7, TimeUnit.DAYS)
                    .build());
        } catch (Exception e) {
            // 降级：直接拼接公开 URL
            return endpoint + "/" + bucket + "/" + objectId;
        }
    }

    @Override
    public boolean exists(String bucket, String objectId) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectId)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureBucket(String bucket) {
        try {
            boolean found = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Bucket created: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("Failed to ensure bucket '{}': {}", bucket, e.getMessage());
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v != null ? v : System.getProperty(key, def);
    }
}

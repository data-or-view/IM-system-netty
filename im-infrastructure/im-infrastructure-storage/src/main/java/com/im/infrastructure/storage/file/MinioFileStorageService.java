package com.im.infrastructure.storage.file;

import com.im.api.IFileStorageService;
import com.im.api.PartInfo;
import com.im.common.exception.FileStorageException;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
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
    private final String accessKey;
    private final String secretKey;
    private final S3MultipartUploader multipartUploader;

    public MinioFileStorageService() {
        this(env("MINIO_ENDPOINT", "http://127.0.0.1:9000"),
                env("MINIO_ACCESS_KEY", "minioadmin"),
                env("MINIO_SECRET_KEY", "minioadmin"));
    }

    public MinioFileStorageService(String endpoint, String accessKey, String secretKey) {
        String ep = endpoint;
        if (ep.endsWith("/")) {
            ep = ep.substring(0, ep.length() - 1);
        }
        this.endpoint = ep;
        this.accessKey = accessKey;
        this.secretKey = secretKey;

        this.client = MinioClient.builder()
                .endpoint(ep)
                .credentials(accessKey, secretKey)
                .build();
        this.multipartUploader = new S3MultipartUploader(ep, "us-east-1", accessKey, secretKey);

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
            String url = presignGetObject(bucket, objectId, 900);
            log.debug("File uploaded: bucket={}, object={}, size={}, url={}", bucket, objectId, data.length, url);
            return url;
        } catch (Exception e) {
            throw new FileStorageException("MinIO upload failed: " + e.getMessage(), e);
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
            throw new FileStorageException("MinIO download failed: " + e.getMessage(), e);
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
            throw new FileStorageException("MinIO delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String getUrl(String bucket, String objectId) {
        return presignGetObject(bucket, objectId, 900);
    }

    @Override
    public String presignGetObject(String bucket, String objectId, int expiresSeconds) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectId)
                    .expiry(expiresSeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new FileStorageException("MinIO presign GET failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String presignPutObject(String bucket, String objectId, String mimeType, int expiresSeconds) {
        try {
            ensureBucket(bucket);
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectId)
                    .expiry(expiresSeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new FileStorageException("MinIO presign PUT failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String presignUploadPart(String bucket, String objectId, String uploadId,
                                    int partNumber, int expiresSeconds) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectId)
                    .expiry(expiresSeconds, TimeUnit.SECONDS)
                    .extraQueryParams(Map.of(
                            "partNumber", String.valueOf(partNumber),
                            "uploadId", uploadId))
                    .build());
        } catch (Exception e) {
            throw new FileStorageException("MinIO presign UploadPart failed: " + e.getMessage(), e);
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

    // ── 分片上传 ──

    @Override
    public String initiateMultipartUpload(String bucket, String objectId) {
        try {
            ensureBucket(bucket);
            String uploadId = multipartUploader.initiateMultipartUpload(bucket, objectId);
            log.debug("Multipart upload initiated: bucket={}, object={}, uploadId={}",
                    bucket, objectId, uploadId);
            return uploadId;
        } catch (Exception e) {
            throw new FileStorageException("MinIO initiateMultipartUpload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadPart(String bucket, String objectId, String uploadId,
                             int partNumber, byte[] data) {
        try {
            String etag = multipartUploader.uploadPart(bucket, objectId, uploadId, partNumber, data);
            log.debug("Part uploaded: bucket={}, object={}, part={}, etag={}",
                    bucket, objectId, partNumber, etag);
            return etag;
        } catch (Exception e) {
            throw new FileStorageException("MinIO uploadPart failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void completeMultipartUpload(String bucket, String objectId, String uploadId,
                                         List<PartInfo> parts) {
        try {
            List<S3MultipartUploader.PartInfo> converted = parts.stream()
                    .map(p -> new S3MultipartUploader.PartInfo(p.partNumber(), p.etag()))
                    .toList();
            multipartUploader.completeMultipartUpload(bucket, objectId, uploadId, converted);
            log.info("Multipart upload completed: bucket={}, object={}, parts={}",
                    bucket, objectId, parts.size());
        } catch (Exception e) {
            throw new FileStorageException("MinIO completeMultipartUpload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void abortMultipartUpload(String bucket, String objectId, String uploadId) {
        try {
            multipartUploader.abortMultipartUpload(bucket, objectId, uploadId);
            log.info("Multipart upload aborted: bucket={}, object={}, uploadId={}",
                    bucket, objectId, uploadId);
        } catch (Exception e) {
            throw new FileStorageException("MinIO abortMultipartUpload failed: " + e.getMessage(), e);
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

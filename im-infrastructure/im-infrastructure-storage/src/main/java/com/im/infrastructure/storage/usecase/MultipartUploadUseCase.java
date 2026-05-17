package com.im.infrastructure.storage.usecase;

import com.im.api.IFileStorageService;
import com.im.api.IFileStorageService.PartInfo;
import com.im.infrastructure.storage.usecase.MultipartUploadUseCase.UploadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 分片上传用例。
 *
 * <p>编排分片上传全流程：init → upload part × N → complete / abort。
 * 使用 {@link ConcurrentMap} 在内存中追踪进行中的上传（单节点适用）。</p>
 *
 * <p>后续可迁至 Redis 以支持多节点。</p>
 */
public class MultipartUploadUseCase {

    private static final String DEFAULT_BUCKET = "im-system";

    private final IFileStorageService fileStorage;

    /** uploadId → 上传上下文 */
    private final ConcurrentMap<String, UploadContext> uploads = new ConcurrentHashMap<>();

    public MultipartUploadUseCase(IFileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 上传上下文。
     */
    public record UploadContext(String bucket, String objectId, String fileId,
                                String fileName, String mimeType) {}

    /**
     * 初始化分片上传。
     *
     * @param fileName 文件名
     * @param mimeType MIME 类型
     * @return {uploadId, fileId}
     */
    public InitResult initiateUpload(String fileName, String mimeType) {
        String ext = extractExtension(fileName);
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String objectId = "uploads/" + fileId + (ext != null ? ext : "");
        String bucket = DEFAULT_BUCKET;

        String uploadId = fileStorage.initiateMultipartUpload(bucket, objectId);
        uploads.put(uploadId, new UploadContext(bucket, objectId, fileId, fileName, mimeType));

        return new InitResult(uploadId, fileId);
    }

    /**
     * 上传分片。
     *
     * @param uploadId   上传 ID
     * @param partNumber 分片编号
     * @param data       分片数据
     * @return ETag
     */
    public String uploadPart(String uploadId, int partNumber, byte[] data) {
        UploadContext ctx = uploads.get(uploadId);
        if (ctx == null) {
            throw new IllegalArgumentException("upload not found: " + uploadId);
        }
        return fileStorage.uploadPart(ctx.bucket(), ctx.objectId(), uploadId, partNumber, data);
    }

    /**
     * 完成分片上传。
     *
     * @param uploadId 上传 ID
     * @param parts    分片列表（需包含所有已上传的分片）
     * @return 可访问的文件 URL
     */
    public CompleteResult completeUpload(String uploadId, List<PartInfo> parts) {
        UploadContext ctx = uploads.remove(uploadId);
        if (ctx == null) {
            throw new IllegalArgumentException("upload not found: " + uploadId);
        }

        // 按 partNumber 排序
        List<PartInfo> sorted = new ArrayList<>(parts);
        sorted.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));

        fileStorage.completeMultipartUpload(ctx.bucket(), ctx.objectId(), uploadId, sorted);
        String fileUrl = fileStorage.getUrl(ctx.bucket(), ctx.objectId());

        return new CompleteResult(fileUrl, ctx.fileId(), ctx.fileName(), ctx.mimeType());
    }

    /**
     * 中止分片上传。
     *
     * @param uploadId 上传 ID
     */
    public void abortUpload(String uploadId) {
        UploadContext ctx = uploads.remove(uploadId);
        if (ctx != null) {
            fileStorage.abortMultipartUpload(ctx.bucket(), ctx.objectId(), uploadId);
        }
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot).toLowerCase();
    }

    public record InitResult(String uploadId, String fileId) {}
    public record CompleteResult(String fileUrl, String fileId, String fileName, String mimeType) {}
}

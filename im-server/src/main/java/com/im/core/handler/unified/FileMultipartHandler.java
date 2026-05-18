package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IFileStorageService.PartInfo;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.infrastructure.storage.usecase.MultipartUploadUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 分片上传 handler：{@code file.multipart.init / upload / complete / abort}。
 *
 * <p>WS 场景：从 params 读取参数，文件 bytes 从 bodyRaw 读取。</p>
 * <p>HTTP 场景：由 {@code HttpRequestAdapter} 解析参数并设置 bodyRaw。</p>
 */
public class FileMultipartHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(FileMultipartHandler.class);

    private final MultipartUploadUseCase multipartUploadUseCase;

    public FileMultipartHandler(MultipartUploadUseCase multipartUploadUseCase) {
        this.multipartUploadUseCase = multipartUploadUseCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "file.multipart.init" -> handleInit(req);
            case "file.multipart.upload" -> handleUpload(req);
            case "file.multipart.complete" -> handleComplete(req);
            case "file.multipart.abort" -> handleAbort(req);
            default -> throw new ImException(ImErrorCode.NOT_FOUND, "unsupported: " + req.operation());
        };
    }

    private Map<String, String> handleInit(ApiRequest req) {
        String fileName = req.getString("fileName");
        String mimeType = req.getString("mimeType");
        if (fileName == null || mimeType == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "fileName and mimeType are required");
        }
        MultipartUploadUseCase.InitResult result = multipartUploadUseCase.initiateUpload(fileName, mimeType);
        log.info("Multipart init: fileId={}, uploadId={}", result.fileId(), result.uploadId());
        return Map.of("uploadId", result.uploadId(), "fileId", result.fileId());
    }

    private Map<String, String> handleUpload(ApiRequest req) {
        String uploadId = req.getString("uploadId");
        int partNumber = req.getInt("partNumber", -1);
        byte[] body = req.bodyRaw();
        if (uploadId == null || partNumber < 1 || body == null || body.length == 0) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "uploadId, partNumber and body are required");
        }
        String etag = multipartUploadUseCase.uploadPart(uploadId, partNumber, body);
        return Map.of("etag", etag);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleComplete(ApiRequest req) {
        String uploadId = req.getString("uploadId");
        if (uploadId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "uploadId is required");
        }
        List<Map<String, Object>> partsMap = (List<Map<String, Object>>) req.params().get("parts");
        if (partsMap == null || partsMap.isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "parts list is required");
        }
        List<PartInfo> parts = partsMap.stream()
                .map(m -> new PartInfo(((Number) m.get("partNumber")).intValue(), (String) m.get("etag")))
                .toList();
        MultipartUploadUseCase.CompleteResult result = multipartUploadUseCase.completeUpload(uploadId, parts);
        log.info("Multipart complete: fileId={}, url={}", result.fileId(), result.fileUrl());
        return Map.of(
                "fileUrl", result.fileUrl(),
                "fileId", result.fileId(),
                "fileName", result.fileName(),
                "mimeType", result.mimeType());
    }

    private Map<String, String> handleAbort(ApiRequest req) {
        String uploadId = req.getString("uploadId");
        if (uploadId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "uploadId is required");
        }
        multipartUploadUseCase.abortUpload(uploadId);
        log.info("Multipart aborted: uploadId={}", uploadId);
        return Map.of("status", "OK");
    }
}

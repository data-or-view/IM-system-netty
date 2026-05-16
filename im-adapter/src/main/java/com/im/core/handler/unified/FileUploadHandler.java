package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.usecase.FileUploadUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 文件上传 handler：{@code file.upload}。
 *
 * <p>WS 场景：fileName/mimeType 从 params 读取，文件 bytes 从 bodyRaw 读取。</p>
 * <p>HTTP 场景：由 {@code HttpRequestAdapter} 解析 multipart 并设置 params + bodyRaw。</p>
 */
public class FileUploadHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(FileUploadHandler.class);

    private final FileUploadUseCase fileUploadUseCase;

    public FileUploadHandler(FileUploadUseCase fileUploadUseCase) {
        this.fileUploadUseCase = fileUploadUseCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        String fileName = req.getString("fileName");
        String mimeType = req.getString("mimeType");
        byte[] body = req.bodyRaw();

        if (body == null || body.length == 0) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "file body is empty");
        }

        FileUploadUseCase.FileUploadResult result = fileUploadUseCase.execute(fileName, mimeType, body);

        log.info("File uploaded: fileId={}, fileName={}, size={}, url={}",
                result.fileId(), result.fileName(), result.fileSize(), result.fileUrl());

        return Map.of("status", "OK",
                "fileUrl", result.fileUrl(),
                "fileId", result.fileId(),
                "fileName", result.fileName(),
                "mimeType", result.mimeType(),
                "fileSize", String.valueOf(result.fileSize()));
    }
}

package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestHandler;
import com.im.common.exception.NotFoundException;
import com.im.common.exception.ValidationException;
import com.im.core.file.DirectFileTransferUseCase;

import java.util.Map;

public class FileDirectTransferHandler implements RequestHandler {

    private final DirectFileTransferUseCase useCase;

    public FileDirectTransferHandler(DirectFileTransferUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        Operation operation = Operation.fromOpName(req.operation());
        if (operation == null) throw new NotFoundException("unsupported: " + req.operation());
        return switch (operation) {
            case FILE_UPLOAD_SIGN -> useCase.signSingleUpload(req.currentUserId(),
                    req.getString("fileName"), req.getLong("fileSize", 0),
                    req.getString("mimeType"), req.getString("hash", ""), req.getString("fileGroup", "file"));
            case FILE_UPLOAD_COMPLETE -> useCase.completeSingleUpload(req.currentUserId(), req.getString("fileId"));
            case FILE_DOWNLOAD_SIGN -> useCase.signDownload(req.currentUserId(), req.getString("fileId"));
            case FILE_MULTIPART_INIT, FILE_MULTIPART_PART_SIGN, FILE_MULTIPART_COMPLETE ->
                    throw new ValidationException("multipart upload is disabled during POST upload migration");
            case FILE_MULTIPART_ABORT -> {
                useCase.abortMultipartUpload(req.currentUserId(), req.getString("uploadId"));
                yield Map.of("status", "OK");
            }
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }
}

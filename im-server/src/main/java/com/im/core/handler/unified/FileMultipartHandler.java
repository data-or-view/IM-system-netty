package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestHandler;
import com.im.common.exception.NotFoundException;
import com.im.core.file.DirectFileTransferUseCase;

import java.util.Map;

/**
 * Server-proxy multipart upload handler for {@code file.multipart.upload}.
 *
 * <p>The multipart session itself is owned by {@link DirectFileTransferUseCase}
 * and persisted through {@code UploadSessionStore}, so upload can hit any node
 * after init in a cluster.</p>
 */
public class FileMultipartHandler implements RequestHandler {

    private final DirectFileTransferUseCase useCase;

    public FileMultipartHandler(DirectFileTransferUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        Operation operation = Operation.fromOpName(req.operation());
        if (operation == null) throw new NotFoundException("unsupported: " + req.operation());
        return switch (operation) {
            case FILE_MULTIPART_UPLOAD -> handleUpload(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Map<String, String> handleUpload(ApiRequest req) {
        String uploadId = req.getString("uploadId");
        int partNumber = req.getInt("partNumber", -1);
        String etag = useCase.uploadMultipartPart(req.currentUserId(), uploadId, partNumber, req.bodyRaw());
        return Map.of("etag", etag);
    }
}

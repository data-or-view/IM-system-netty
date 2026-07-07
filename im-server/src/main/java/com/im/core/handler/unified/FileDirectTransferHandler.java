package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.PartInfo;
import com.im.api.RequestHandler;
import com.im.common.exception.NotFoundException;
import com.im.common.exception.ValidationException;
import com.im.core.file.DirectFileTransferUseCase;

import java.util.List;
import java.util.Map;

public class FileDirectTransferHandler implements RequestHandler {

    private final DirectFileTransferUseCase useCase;

    public FileDirectTransferHandler(DirectFileTransferUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "file.upload.sign" -> useCase.signSingleUpload(req.currentUserId(),
                    req.getString("fileName"), req.getLong("fileSize", 0),
                    req.getString("mimeType"), req.getString("hash", ""), req.getString("fileGroup", "file"));
            case "file.upload.complete" -> useCase.completeSingleUpload(req.currentUserId(), req.getString("fileId"));
            case "file.download.sign" -> useCase.signDownload(req.currentUserId(), req.getString("fileId"));
            case "file.multipart.init" -> useCase.initiateMultipartUpload(req.currentUserId(),
                    req.getString("fileName"), req.getLong("fileSize", 0),
                    req.getString("mimeType"), req.getString("hash", ""), req.getString("fileGroup", "file"));
            case "file.multipart.part.sign" -> useCase.signMultipartPart(req.currentUserId(),
                    req.getString("uploadId"), req.getInt("partNumber", -1));
            case "file.multipart.complete" -> useCase.completeMultipartUpload(req.currentUserId(),
                    req.getString("uploadId"), toParts(req));
            case "file.multipart.abort" -> {
                useCase.abortMultipartUpload(req.currentUserId(), req.getString("uploadId"));
                yield Map.of("status", "OK");
            }
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private static List<PartInfo> toParts(ApiRequest req) {
        Object raw = req.params().get("parts");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ValidationException("parts list is required");
        }
        return list.stream()
                .map(FileDirectTransferHandler::toPartInfo)
                .toList();
    }

    private static PartInfo toPartInfo(Object item) {
        if (!(item instanceof Map<?, ?> part)) {
            throw new ValidationException("part info is invalid");
        }
        Object partNumberValue = part.get("partNumber");
        if (!(partNumberValue instanceof Number number) || number.intValue() < 1) {
            throw new ValidationException("partNumber is required");
        }
        Object etagValue = part.get("etag");
        if (!(etagValue instanceof String etag) || etag.isBlank()) {
            throw new ValidationException("etag is required");
        }
        return new PartInfo(number.intValue(), etag.trim());
    }
}

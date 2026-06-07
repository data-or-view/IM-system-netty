package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.PartInfo;
import com.im.api.RequestHandler;
import com.im.common.exception.NotFoundException;
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

    @SuppressWarnings("unchecked")
    private static List<PartInfo> toParts(ApiRequest req) {
        Object raw = req.params().get("parts");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    Map<String, Object> part = (Map<String, Object>) item;
                    Object partNumber = part.get("partNumber");
                    Object etag = part.get("etag");
                    return new PartInfo(((Number) partNumber).intValue(), String.valueOf(etag));
                })
                .toList();
    }
}

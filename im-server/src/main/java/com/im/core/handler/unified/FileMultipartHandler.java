package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestHandler;
import com.im.common.exception.NotFoundException;
import com.im.common.exception.ValidationException;
import com.im.core.file.DirectFileTransferUseCase;

import java.util.Objects;

/**
 * Legacy server-proxy multipart upload handler.
 *
 * <p>The endpoint remains registered only to tell clients to migrate to the
 * exact-size object-storage POST upload flow.</p>
 */
public class FileMultipartHandler implements RequestHandler {

    public FileMultipartHandler(DirectFileTransferUseCase useCase) {
        Objects.requireNonNull(useCase, "useCase");
    }

    @Override
    public Object handle(ApiRequest req) {
        Operation operation = Operation.fromOpName(req.operation());
        if (operation == null) throw new NotFoundException("unsupported: " + req.operation());
        if (operation != Operation.FILE_MULTIPART_UPLOAD) {
            throw new NotFoundException("unsupported: " + req.operation());
        }
        throw new ValidationException("multipart upload is disabled during POST upload migration");
    }

}

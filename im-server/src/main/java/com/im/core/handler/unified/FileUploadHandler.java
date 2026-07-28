package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.RequestHandler;
import com.im.common.exception.ValidationException;
import com.im.core.file.DirectFileTransferUseCase;

import java.util.Objects;

/**
 * Legacy proxy upload handler.
 *
 * <p>New uploads use the authenticated POST-policy sign and complete endpoints. This
 * legacy route is retained only to return a deterministic migration error.</p>
 */
public class FileUploadHandler implements RequestHandler {

    public FileUploadHandler(DirectFileTransferUseCase fileUploadUseCase) {
        Objects.requireNonNull(fileUploadUseCase, "fileUploadUseCase");
    }

    @Override
    public Object handle(ApiRequest req) {
        throw new ValidationException("proxy file upload is disabled; use POST upload migration");
    }
}

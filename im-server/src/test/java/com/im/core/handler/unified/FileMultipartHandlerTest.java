package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IFileStorageService;
import com.im.api.Operation;
import com.im.common.exception.ValidationException;
import com.im.infrastructure.storage.usecase.MultipartUploadUseCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FileMultipartHandlerTest {

    @Test
    void completeRejectsMalformedPartInfo() {
        MultipartUploadUseCase useCase = new MultipartUploadUseCase(new FakeStorage());
        FileMultipartHandler handler = new FileMultipartHandler(useCase);
        String uploadId = useCase.initiateUpload("a.txt", "text/plain").uploadId();
        ApiRequest request = new ApiRequest(Operation.FILE_MULTIPART_COMPLETE,
                Map.of("uploadId", uploadId, "parts", List.of(Map.of("partNumber", 1))),
                Map.of(), null, null);

        assertThrows(ValidationException.class, () -> handler.handle(request));
    }

    private static class FakeStorage implements IFileStorageService {
        @Override public String upload(String bucket, String objectId, byte[] data, String mimeType) { return "url"; }
        @Override public byte[] download(String bucket, String objectId) { return new byte[0]; }
        @Override public void delete(String bucket, String objectId) {}
        @Override public String getUrl(String bucket, String objectId) { return "url"; }
        @Override public boolean exists(String bucket, String objectId) { return false; }
        @Override public String initiateMultipartUpload(String bucket, String objectId) { return "upload-1"; }
    }
}

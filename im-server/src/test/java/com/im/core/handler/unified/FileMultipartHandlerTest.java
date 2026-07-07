package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IFileStorageService;
import com.im.api.Operation;
import com.im.core.file.DirectFileTransferUseCase;
import com.im.core.file.FileObjectMetadata;
import com.im.core.file.FileObjectMetadataStore;
import com.im.core.file.MultipartSignResult;
import com.im.core.file.UploadSession;
import com.im.core.file.UploadSessionStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileMultipartHandlerTest {

    @Test
    void uploadUsesStoredMultipartSession() {
        FakeStorage storage = new FakeStorage();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(
                storage,
                new InMemoryUploadSessionStore(),
                new InMemoryFileObjectMetadataStore(),
                "im-system",
                900);
        FileMultipartHandler handler = new FileMultipartHandler(useCase);
        MultipartSignResult init = useCase.initiateMultipartUpload("u1", "a.txt", 3, "text/plain", "", "file");
        ApiRequest request = new ApiRequest(Operation.FILE_MULTIPART_UPLOAD,
                Map.of("uploadId", init.uploadId(), "partNumber", 1),
                Map.of(), null, new byte[]{1, 2, 3});
        request.setAttribute(ApiRequest.ATTR_USER_ID, "u1");

        Object response = handler.handle(request);

        assertEquals(Map.of("etag", "\"etag-1\""), response);
        assertEquals(init.uploadId(), storage.lastUploadId);
        assertEquals(1, storage.lastPartNumber);
    }

    private static class FakeStorage implements IFileStorageService {
        String lastUploadId;
        int lastPartNumber;

        @Override public String upload(String bucket, String objectId, byte[] data, String mimeType) { return "url"; }
        @Override public byte[] download(String bucket, String objectId) { return new byte[0]; }
        @Override public void delete(String bucket, String objectId) {}
        @Override public String getUrl(String bucket, String objectId) { return "url"; }
        @Override public boolean exists(String bucket, String objectId) { return true; }
        @Override public String initiateMultipartUpload(String bucket, String objectId) { return "upload-1"; }
        @Override
        public String uploadPart(String bucket, String objectId, String uploadId, int partNumber, byte[] data) {
            lastUploadId = uploadId;
            lastPartNumber = partNumber;
            return "\"etag-" + partNumber + "\"";
        }
    }

    private static final class InMemoryUploadSessionStore implements UploadSessionStore {
        private final Map<String, UploadSession> byFileId = new HashMap<>();
        private final Map<String, UploadSession> byUploadId = new HashMap<>();

        @Override
        public void save(UploadSession session) {
            byFileId.put(session.fileId(), session);
            if (session.uploadId() != null) byUploadId.put(session.uploadId(), session);
        }

        @Override
        public UploadSession getByFileId(String fileId) {
            return byFileId.get(fileId);
        }

        @Override
        public UploadSession getByUploadId(String uploadId) {
            return byUploadId.get(uploadId);
        }

        @Override
        public void delete(UploadSession session) {
            byFileId.remove(session.fileId());
            if (session.uploadId() != null) byUploadId.remove(session.uploadId());
        }
    }

    private static final class InMemoryFileObjectMetadataStore implements FileObjectMetadataStore {
        @Override
        public void save(FileObjectMetadata metadata) {
        }

        @Override
        public FileObjectMetadata findByFileId(String fileId) {
            return null;
        }
    }
}

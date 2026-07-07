package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IFileStorageService;
import com.im.api.Operation;
import com.im.api.PartInfo;
import com.im.common.exception.ValidationException;
import com.im.core.file.DirectFileTransferUseCase;
import com.im.core.file.FileObjectMetadata;
import com.im.core.file.FileObjectMetadataStore;
import com.im.core.file.MultipartSignResult;
import com.im.core.file.UploadSession;
import com.im.core.file.UploadSessionStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FileDirectTransferHandlerTest {

    @Test
    void multipartCompleteRejectsMissingEtag() {
        Fixture fixture = new Fixture();
        MultipartSignResult init = fixture.useCase.initiateMultipartUpload(
                "u1", "a.txt", 3, "text/plain", "", "file");
        ApiRequest request = completeRequest(init.uploadId(), List.of(Map.of("partNumber", 1)));

        assertThrows(ValidationException.class, () -> fixture.handler.handle(request));
    }

    @Test
    void multipartCompleteRejectsNonMapPart() {
        Fixture fixture = new Fixture();
        MultipartSignResult init = fixture.useCase.initiateMultipartUpload(
                "u1", "a.txt", 3, "text/plain", "", "file");
        ApiRequest request = completeRequest(init.uploadId(), List.of("bad-part"));

        assertThrows(ValidationException.class, () -> fixture.handler.handle(request));
    }

    private static ApiRequest completeRequest(String uploadId, List<?> parts) {
        ApiRequest request = new ApiRequest(Operation.FILE_MULTIPART_COMPLETE,
                Map.of("uploadId", uploadId, "parts", parts), Map.of(), null, null);
        request.setAttribute(ApiRequest.ATTR_USER_ID, "u1");
        return request;
    }

    private static final class Fixture {
        private final DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(
                new FakeStorage(),
                new InMemoryUploadSessionStore(),
                new InMemoryFileObjectMetadataStore(),
                "im-system",
                900);
        private final FileDirectTransferHandler handler = new FileDirectTransferHandler(useCase);
    }

    private static final class FakeStorage implements IFileStorageService {
        @Override public String upload(String bucket, String objectId, byte[] data, String mimeType) { return getUrl(bucket, objectId); }
        @Override public byte[] download(String bucket, String objectId) { return new byte[0]; }
        @Override public void delete(String bucket, String objectId) {}
        @Override public String getUrl(String bucket, String objectId) { return "https://oss.test/" + bucket + "/" + objectId; }
        @Override public boolean exists(String bucket, String objectId) { return true; }
        @Override public String initiateMultipartUpload(String bucket, String objectId) { return "upload-1"; }
        @Override public void completeMultipartUpload(String bucket, String objectId, String uploadId, List<PartInfo> parts) {}
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

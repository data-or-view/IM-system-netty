package com.im.core.file;

import com.im.api.IFileStorageService;
import com.im.api.PartInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectFileTransferUseCaseTest {

    @Test
    void signAndCompleteSingleUploadWithoutProxyingBytes() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);

        PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "h1", "file");

        assertEquals("PUT", signed.method());
        assertTrue(signed.uploadUrl().contains("/im-system/"));
        assertTrue(sessions.byFileId.containsKey(signed.fileId()));
        assertArrayEquals(new byte[0], storage.lastUploadedBytes);

        FileUploadCompleteResult completed = useCase.completeSingleUpload("u1", signed.fileId());

        assertEquals(signed.fileId(), completed.fileId());
        assertEquals("a.txt", completed.fileName());
        assertEquals(3, completed.fileSize());
        assertTrue(completed.fileUrl().contains(signed.objectKey()));
        assertNotNull(metadata.saved.get(signed.fileId()));
        assertFalse(sessions.byFileId.containsKey(signed.fileId()));
    }

    @Test
    void multipartPartSignReturnsOssUrlAndCompletePersistsMetadata() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);

        MultipartSignResult init = useCase.initiateMultipartUpload("u1", "video.mp4", 9, "video/mp4", "", "video");
        PresignedPartResult part = useCase.signMultipartPart("u1", init.uploadId(), 2);

        assertEquals(2, part.partNumber());
        assertTrue(part.uploadUrl().contains("partNumber=2"));
        assertTrue(part.uploadUrl().contains("uploadId=" + init.uploadId()));

        FileUploadCompleteResult completed = useCase.completeMultipartUpload("u1", init.uploadId(),
                List.of(new PartInfo(2, "\"etag-2\"")));

        assertEquals(init.fileId(), completed.fileId());
        assertEquals("video.mp4", completed.fileName());
        assertNotNull(metadata.saved.get(init.fileId()));
        assertFalse(sessions.byUploadId.containsKey(init.uploadId()));
    }

    @Test
    void downloadSignRequiresFileOwner() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);
        metadata.save(new FileObjectMetadata("file1", "owner", "im-system", "uploads/file1.txt",
                "a.txt", 3, "text/plain", "", "minio", "file", 1));

        com.im.common.exception.ImException ex = assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.signDownload("other", "file1"));

        assertEquals(com.im.common.enums.ImErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    private static final class FakeStorage implements IFileStorageService {
        byte[] lastUploadedBytes = new byte[0];

        @Override
        public String upload(String bucket, String objectId, byte[] data, String mimeType) {
            lastUploadedBytes = data;
            return getUrl(bucket, objectId);
        }

        @Override
        public byte[] download(String bucket, String objectId) {
            return new byte[0];
        }

        @Override
        public void delete(String bucket, String objectId) {
        }

        @Override
        public String getUrl(String bucket, String objectId) {
            return "https://oss.test/" + bucket + "/" + objectId;
        }

        @Override
        public boolean exists(String bucket, String objectId) {
            return true;
        }

        @Override
        public String initiateMultipartUpload(String bucket, String objectId) {
            return "upload-1";
        }

        @Override
        public void completeMultipartUpload(String bucket, String objectId, String uploadId, List<PartInfo> parts) {
        }

        @Override
        public String presignPutObject(String bucket, String objectId, String mimeType, int expiresSeconds) {
            return "https://oss.test/" + bucket + "/" + objectId + "?X-Amz-Signature=put";
        }

        @Override
        public String presignUploadPart(String bucket, String objectId, String uploadId, int partNumber, int expiresSeconds) {
            return "https://oss.test/" + bucket + "/" + objectId + "?partNumber=" + partNumber + "&uploadId=" + uploadId;
        }
    }

    private static final class InMemoryUploadSessionStore implements UploadSessionStore {
        final Map<String, UploadSession> byFileId = new HashMap<>();
        final Map<String, UploadSession> byUploadId = new HashMap<>();

        @Override
        public void save(UploadSession session) {
            byFileId.put(session.fileId(), session);
            if (session.uploadId() != null) {
                byUploadId.put(session.uploadId(), session);
            }
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
            if (session.uploadId() != null) {
                byUploadId.remove(session.uploadId());
            }
        }
    }

    private static final class InMemoryFileObjectMetadataStore implements FileObjectMetadataStore {
        final Map<String, FileObjectMetadata> saved = new HashMap<>();

        @Override
        public void save(FileObjectMetadata metadata) {
            saved.put(metadata.fileId(), metadata);
        }

        @Override
        public FileObjectMetadata findByFileId(String fileId) {
            return saved.get(fileId);
        }
    }
}

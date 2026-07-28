package com.im.core.file;

import com.im.api.FileObjectStat;
import com.im.api.IFileStorageService;
import com.im.api.PartInfo;
import com.im.api.PresignedPostPolicy;
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

        assertEquals("POST", signed.method());
        assertEquals("file", signed.fileField());
        assertEquals(3, storage.policyMaxBytes);
        assertEquals("https://oss.test/im-system", signed.uploadUrl());
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
    void completionDeletesObjectWhenActualSizeDiffers() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);
        PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "", "file");
        storage.stat = new FileObjectStat(4, "text/plain");

        assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.completeSingleUpload("u1", signed.fileId()));

        assertTrue(storage.deleteCalled);
        assertFalse(sessions.byFileId.containsKey(signed.fileId()));
        assertNull(metadata.saved.get(signed.fileId()));
    }

    @Test
    void completionDeletesObjectWhenContentTypeDiffers() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(
                storage, sessions, new InMemoryFileObjectMetadataStore(), "im-system", 900);
        PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "", "file");
        storage.stat = new FileObjectStat(3, "application/octet-stream");

        assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.completeSingleUpload("u1", signed.fileId()));

        assertTrue(storage.deleteCalled);
        assertFalse(sessions.byFileId.containsKey(signed.fileId()));
    }

    @Test
    void missingObjectDoesNotPersistMetadataOrConsumeSession() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);
        PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "", "file");
        storage.stat = null;

        assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.completeSingleUpload("u1", signed.fileId()));

        assertFalse(storage.deleteCalled);
        assertTrue(sessions.byFileId.containsKey(signed.fileId()));
        assertNull(metadata.saved.get(signed.fileId()));
    }

    @Test
    void failedMetadataPersistenceLeavesSessionForCompletionRetry() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        metadata.failNextSave = true;
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);
        PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "", "file");

        assertThrows(RuntimeException.class, () -> useCase.completeSingleUpload("u1", signed.fileId()));
        assertTrue(sessions.byFileId.containsKey(signed.fileId()));

        assertEquals(signed.fileId(), useCase.completeSingleUpload("u1", signed.fileId()).fileId());
        assertFalse(sessions.byFileId.containsKey(signed.fileId()));
    }

    @Test
    void completedUploadCanBeRetriedAfterResponseSigningFails() {
        FakeStorage storage = new FakeStorage();
        storage.failNextPresignGet = true;
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);
        PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "", "file");

        assertThrows(RuntimeException.class, () -> useCase.completeSingleUpload("u1", signed.fileId()));
        assertFalse(sessions.byFileId.containsKey(signed.fileId()));
        assertNotNull(metadata.saved.get(signed.fileId()));

        assertEquals(signed.fileId(), useCase.completeSingleUpload("u1", signed.fileId()).fileId());
    }

    @Test
    void completedUploadRetryRequiresPersistedMetadataOwner() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);
        PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "", "file");
        useCase.completeSingleUpload("u1", signed.fileId());

        com.im.common.exception.ImException ex = assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.completeSingleUpload("u2", signed.fileId()));

        assertEquals(com.im.common.enums.ImErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void completionRequiresUploadOwner() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(
                storage, sessions, new InMemoryFileObjectMetadataStore(), "im-system", 900);
        PresignedUploadResult signed = useCase.signSingleUpload("u1", "a.txt", 3, "text/plain", "", "file");

        com.im.common.exception.ImException ex = assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.completeSingleUpload("u2", signed.fileId()));

        assertEquals(com.im.common.enums.ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertTrue(sessions.byFileId.containsKey(signed.fileId()));
    }

    @Test
    void multipartInitIsDisabledDuringPostUploadMigration() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);

        com.im.common.exception.ImException ex = assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.initiateMultipartUpload("u1", "video.mp4", 9, "video/mp4", "", "video"));

        assertTrue(ex.getMessage().contains("POST upload migration"));
    }

    @Test
    void multipartPartProxyUploadIsDisabledDuringPostUploadMigration() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);
        com.im.common.exception.ImException ex = assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.uploadMultipartPart("u1", "upload-1", 3, new byte[]{1, 2, 3}));

        assertTrue(ex.getMessage().contains("POST upload migration"));
    }

    @Test
    void multipartAbortRetainsOwnerCheckForExistingSessions() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(
                storage, sessions, new InMemoryFileObjectMetadataStore(), "im-system", 900);
        UploadSession session = new UploadSession("file-1", "upload-1", "im-system", "uploads/file-1.txt",
                "u1", "a.txt", 3, "text/plain", "", "file", true);
        sessions.save(session);

        com.im.common.exception.ImException ex = assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.abortMultipartUpload("u2", "upload-1"));
        assertEquals(com.im.common.enums.ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertFalse(storage.abortCalled);

        useCase.abortMultipartUpload("u1", "upload-1");
        assertTrue(storage.abortCalled);
        assertNull(sessions.getByUploadId("upload-1"));
    }

    @Test
    void postUploadSigningRejectsFileLargerThanConfiguredLimit() {
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(
                new FakeStorage(),
                new InMemoryUploadSessionStore(),
                new InMemoryFileObjectMetadataStore(),
                "im-system",
                900,
                2);

        com.im.common.exception.ImException ex = assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.signSingleUpload("u1", "video.mp4", 3, "video/mp4", "", "video"));

        assertEquals(com.im.common.enums.ImErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void proxySingleUploadIsDisabled() {
        FakeStorage storage = new FakeStorage();
        InMemoryUploadSessionStore sessions = new InMemoryUploadSessionStore();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(storage, sessions, metadata, "im-system", 900);

        com.im.common.exception.ImException ex = assertThrows(com.im.common.exception.ImException.class,
                () -> useCase.uploadSingleFile("u1", "a.txt", "text/plain", new byte[]{1, 2, 3}, "", "file"));

        assertTrue(ex.getMessage().contains("POST upload migration"));
    }

    @Test
    void downloadSignUsesConfiguredShortTtl() {
        FakeStorage storage = new FakeStorage();
        InMemoryFileObjectMetadataStore metadata = new InMemoryFileObjectMetadataStore();
        DirectFileTransferUseCase useCase = new DirectFileTransferUseCase(
                storage, new InMemoryUploadSessionStore(), metadata, "im-system", 123);
        metadata.save(new FileObjectMetadata("file1", "owner", "im-system", "uploads/file1.txt",
                "a.txt", 3, "text/plain", "", "minio", "file", 1));

        FileDownloadSignResult download = useCase.signDownload("owner", "file1");

        assertTrue(download.fileUrl().contains("expires=123"));
        assertEquals(123, storage.lastGetExpiresSeconds);
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
        String lastUploadId;
        int lastPartNumber;
        int lastGetExpiresSeconds;
        boolean failNextPresignGet;
        long policyMaxBytes;
        FileObjectStat stat = new FileObjectStat(3, "text/plain");
        boolean deleteCalled;
        boolean abortCalled;

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
            deleteCalled = true;
        }

        @Override
        public String getUrl(String bucket, String objectId) {
            return "https://oss.test/" + bucket + "/" + objectId;
        }

        @Override
        public String presignGetObject(String bucket, String objectId, int expiresSeconds) {
            if (failNextPresignGet) {
                failNextPresignGet = false;
                throw new IllegalStateException("temporary signing failure");
            }
            lastGetExpiresSeconds = expiresSeconds;
            return "https://oss.test/" + bucket + "/" + objectId + "?expires=" + expiresSeconds;
        }

        @Override
        public boolean exists(String bucket, String objectId) {
            return true;
        }

        @Override
        public PresignedPostPolicy presignPostPolicy(String bucket, String objectKey, String contentType,
                                                      long exactSizeBytes, int expiresSeconds) {
            policyMaxBytes = exactSizeBytes;
            return new PresignedPostPolicy("https://oss.test/" + bucket,
                    Map.of("key", objectKey, "Content-Type", contentType), "file");
        }

        @Override
        public FileObjectStat statObject(String bucket, String objectKey) {
            return stat;
        }

        @Override
        public void abortMultipartUpload(String bucket, String objectId, String uploadId) {
            abortCalled = true;
        }

        @Override
        public String initiateMultipartUpload(String bucket, String objectId) {
            return "upload-1";
        }

        @Override
        public void completeMultipartUpload(String bucket, String objectId, String uploadId, List<PartInfo> parts) {
        }

        @Override
        public String uploadPart(String bucket, String objectId, String uploadId, int partNumber, byte[] data) {
            lastUploadId = uploadId;
            lastPartNumber = partNumber;
            lastUploadedBytes = data;
            return "\"etag-" + partNumber + "\"";
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
        boolean failNextSave;

        @Override
        public void save(FileObjectMetadata metadata) {
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("metadata store unavailable");
            }
            saved.put(metadata.fileId(), metadata);
        }

        @Override
        public FileObjectMetadata findByFileId(String fileId) {
            return saved.get(fileId);
        }
    }
}

package com.im.core.file;

import com.im.api.FileObjectStat;
import com.im.api.IFileStorageService;
import com.im.api.PartInfo;
import com.im.api.PresignedPostPolicy;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.common.exception.ValidationException;
import com.im.common.id.IdGenerator;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class DirectFileTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(DirectFileTransferUseCase.class);

    private static final String DEFAULT_ENGINE = "minio";
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final String POST_UPLOAD_MIGRATION_MESSAGE =
            "multipart upload is disabled during POST upload migration";

    private final IFileStorageService fileStorage;
    private final UploadSessionStore uploadSessionStore;
    private final FileObjectMetadataStore metadataStore;
    private final String bucket;
    private final int presignExpiresSeconds;
    private final long maxUploadBytes;

    public DirectFileTransferUseCase(IFileStorageService fileStorage,
                                     UploadSessionStore uploadSessionStore,
                                     FileObjectMetadataStore metadataStore,
                                     String bucket,
                                     int presignExpiresSeconds) {
        this(fileStorage, uploadSessionStore, metadataStore, bucket, presignExpiresSeconds,
                DEFAULT_MAX_UPLOAD_BYTES);
    }

    public DirectFileTransferUseCase(IFileStorageService fileStorage,
                                     UploadSessionStore uploadSessionStore,
                                     FileObjectMetadataStore metadataStore,
                                     String bucket,
                                     int presignExpiresSeconds,
                                     long maxUploadBytes) {
        this.fileStorage = fileStorage;
        this.uploadSessionStore = uploadSessionStore;
        this.metadataStore = metadataStore;
        this.bucket = bucket;
        this.presignExpiresSeconds = presignExpiresSeconds;
        this.maxUploadBytes = maxUploadBytes > 0 ? maxUploadBytes : DEFAULT_MAX_UPLOAD_BYTES;
    }

    public PresignedUploadResult signSingleUpload(String userId, String fileName, long fileSize,
                                                  String contentType, String hash, String fileGroup) {
        validateUser(userId);
        validateFile(fileName, fileSize);
        String fileId = IdGenerator.fileId();
        String objectKey = objectKey(fileId, fileName);
        String mimeType = normalizeContentType(contentType);
        UploadSession session = new UploadSession(fileId, null, bucket, objectKey, userId, fileName,
                fileSize, mimeType, hash, normalizeFileGroup(fileGroup), false);
        PresignedPostPolicy policy = fileStorage.presignPostPolicy(
                bucket, objectKey, mimeType, fileSize, presignExpiresSeconds);
        uploadSessionStore.save(session);
        log.info(StructuredLog.event(LogEvents.FILE_UPLOAD_SIGNED,
                LogFields.USER_ID, userId,
                LogFields.FILE_ID, fileId,
                LogFields.FILE_SIZE, fileSize,
                LogFields.FILE_GROUP, session.fileGroup()));
        return new PresignedUploadResult(fileId, objectKey, policy.uploadUrl(), "POST",
                policy.formFields(), policy.fileField(), presignExpiresSeconds);
    }

    public FileUploadCompleteResult completeSingleUpload(String userId, String fileId) {
        validateUser(userId);
        UploadSession session = uploadSessionStore.getByFileId(fileId);
        if (session == null) {
            FileObjectMetadata metadata = metadataStore.findByFileId(fileId);
            if (metadata == null) {
                throw new ImException(ImErrorCode.NOT_FOUND, "upload session not found");
            }
            ensureOwner(metadata, userId);
            return completedResult(metadata);
        }
        if (session.multipart()) {
            throw new ImException(ImErrorCode.NOT_FOUND, "upload session not found");
        }
        return complete(session, userId);
    }

    public FileUploadCompleteResult uploadSingleFile(String userId, String fileName, String contentType,
                                                     byte[] data, String hash, String fileGroup) {
        throw new ValidationException("proxy file upload is disabled; use POST upload migration");
    }

    public MultipartSignResult initiateMultipartUpload(String userId, String fileName, long fileSize,
                                                       String contentType, String hash, String fileGroup) {
        throw postUploadMigrationRequired();
    }

    public PresignedPartResult signMultipartPart(String userId, String uploadId, int partNumber) {
        throw postUploadMigrationRequired();
    }

    public String uploadMultipartPart(String userId, String uploadId, int partNumber, byte[] data) {
        throw postUploadMigrationRequired();
    }

    public FileUploadCompleteResult completeMultipartUpload(String userId, String uploadId, List<PartInfo> parts) {
        throw postUploadMigrationRequired();
    }

    public void abortMultipartUpload(String userId, String uploadId) {
        validateUser(userId);
        UploadSession session = uploadSessionStore.getByUploadId(uploadId);
        if (session == null) {
            return;
        }
        ensureOwner(session, userId);
        fileStorage.abortMultipartUpload(session.bucket(), session.objectKey(), uploadId);
        uploadSessionStore.delete(session);
        log.info(StructuredLog.event(LogEvents.FILE_MULTIPART_ABORTED,
                LogFields.USER_ID, userId,
                LogFields.FILE_ID, session.fileId(),
                LogFields.UPLOAD_ID, uploadId,
                LogFields.FILE_GROUP, session.fileGroup()));
    }

    public FileDownloadSignResult signDownload(String userId, String fileId) {
        validateUser(userId);
        FileObjectMetadata metadata = metadataStore.findByFileId(fileId);
        if (metadata == null) {
            throw new ImException(ImErrorCode.NOT_FOUND, "file not found");
        }
        if (!metadata.userId().equals(userId)) {
            throw new ImException(ImErrorCode.FORBIDDEN, "file does not belong to current user");
        }
        String url = fileStorage.presignGetObject(metadata.bucket(), metadata.objectKey(), presignExpiresSeconds);
        return new FileDownloadSignResult(metadata.fileId(), metadata.fileName(), url,
                metadata.fileSize(), metadata.contentType());
    }

    private FileUploadCompleteResult complete(UploadSession session, String userId) {
        ensureOwner(session, userId);
        FileObjectStat actual = fileStorage.statObject(session.bucket(), session.objectKey());
        if (actual == null) {
            throw new ImException(ImErrorCode.NOT_FOUND, "uploaded object not found");
        }
        if (actual.sizeBytes() != session.fileSize()
                || !session.contentType().equals(actual.contentType())) {
            fileStorage.delete(session.bucket(), session.objectKey());
            uploadSessionStore.delete(session);
            throw new ImException(ImErrorCode.BAD_REQUEST,
                    "uploaded object does not match upload session");
        }
        FileObjectMetadata metadata = new FileObjectMetadata(session.fileId(), session.userId(),
                session.bucket(), session.objectKey(), session.fileName(), session.fileSize(),
                session.contentType(), session.hash(), DEFAULT_ENGINE, session.fileGroup(),
                System.currentTimeMillis());
        metadataStore.save(metadata);
        uploadSessionStore.delete(session);
        log.info(StructuredLog.event(LogEvents.FILE_UPLOAD_COMPLETED,
                LogFields.USER_ID, userId,
                LogFields.FILE_ID, session.fileId(),
                LogFields.UPLOAD_ID, session.uploadId(),
                LogFields.FILE_SIZE, session.fileSize(),
                LogFields.FILE_GROUP, session.fileGroup()));
        return completedResult(metadata);
    }

    private static void validateUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "login required");
        }
    }

    private void validateFile(String fileName, long fileSize) {
        if (fileName == null || fileName.isBlank() || fileSize <= 0) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "fileName and fileSize are required");
        }
        if (fileSize > maxUploadBytes) {
            throw new ImException(ImErrorCode.BAD_REQUEST,
                    "file too large: " + fileSize + " (max " + maxUploadBytes + ")");
        }
    }

    private static void ensureOwner(UploadSession session, String userId) {
        if (!session.userId().equals(userId)) {
            throw new ImException(ImErrorCode.FORBIDDEN, "file upload does not belong to current user");
        }
    }

    private static void ensureOwner(FileObjectMetadata metadata, String userId) {
        if (!metadata.userId().equals(userId)) {
            throw new ImException(ImErrorCode.FORBIDDEN, "file upload does not belong to current user");
        }
    }

    private FileUploadCompleteResult completedResult(FileObjectMetadata metadata) {
        return new FileUploadCompleteResult(fileStorage.presignGetObject(
                metadata.bucket(), metadata.objectKey(), presignExpiresSeconds),
                metadata.fileId(), metadata.objectKey(), metadata.fileName(), metadata.contentType(), metadata.fileSize());
    }

    private static String objectKey(String fileId, String fileName) {
        String ext = extractExtension(fileName);
        return "uploads/" + fileId + (ext != null ? ext : "");
    }

    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        String ext = fileName.substring(dot).toLowerCase();
        return ext.matches("\\.(jpg|jpeg|png|gif|webp|bmp|mp4|mp3|wav|ogg|pdf|doc|docx|xls|xlsx|zip|txt|json|csv)")
                ? ext : null;
    }

    private static String normalizeContentType(String contentType) {
        return contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream";
    }

    private static ValidationException postUploadMigrationRequired() {
        return new ValidationException(POST_UPLOAD_MIGRATION_MESSAGE);
    }

    private static String normalizeFileGroup(String fileGroup) {
        return fileGroup != null && !fileGroup.isBlank() ? fileGroup : "file";
    }
}

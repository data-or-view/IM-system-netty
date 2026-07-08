package com.im.core.file;

import com.im.api.IFileStorageService;
import com.im.api.PartInfo;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.common.id.IdGenerator;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class DirectFileTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(DirectFileTransferUseCase.class);

    private static final String DEFAULT_ENGINE = "minio";
    private static final long DEFAULT_MAX_PROXY_UPLOAD_BYTES = 100L * 1024 * 1024;

    private final IFileStorageService fileStorage;
    private final UploadSessionStore uploadSessionStore;
    private final FileObjectMetadataStore metadataStore;
    private final String bucket;
    private final int presignExpiresSeconds;
    private final long maxProxyUploadBytes;

    public DirectFileTransferUseCase(IFileStorageService fileStorage,
                                     UploadSessionStore uploadSessionStore,
                                     FileObjectMetadataStore metadataStore,
                                     String bucket,
                                     int presignExpiresSeconds) {
        this(fileStorage, uploadSessionStore, metadataStore, bucket, presignExpiresSeconds,
                DEFAULT_MAX_PROXY_UPLOAD_BYTES);
    }

    public DirectFileTransferUseCase(IFileStorageService fileStorage,
                                     UploadSessionStore uploadSessionStore,
                                     FileObjectMetadataStore metadataStore,
                                     String bucket,
                                     int presignExpiresSeconds,
                                     long maxProxyUploadBytes) {
        this.fileStorage = fileStorage;
        this.uploadSessionStore = uploadSessionStore;
        this.metadataStore = metadataStore;
        this.bucket = bucket;
        this.presignExpiresSeconds = presignExpiresSeconds;
        this.maxProxyUploadBytes = maxProxyUploadBytes > 0 ? maxProxyUploadBytes : DEFAULT_MAX_PROXY_UPLOAD_BYTES;
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
        uploadSessionStore.save(session);
        String uploadUrl = fileStorage.presignPutObject(bucket, objectKey, mimeType, presignExpiresSeconds);
        log.info(StructuredLog.event(LogEvents.FILE_UPLOAD_SIGNED,
                LogFields.USER_ID, userId,
                LogFields.FILE_ID, fileId,
                LogFields.FILE_SIZE, fileSize,
                LogFields.FILE_GROUP, session.fileGroup()));
        return new PresignedUploadResult(fileId, objectKey, uploadUrl, "PUT",
                Map.of("Content-Type", mimeType), presignExpiresSeconds);
    }

    public FileUploadCompleteResult completeSingleUpload(String userId, String fileId) {
        validateUser(userId);
        UploadSession session = uploadSessionStore.getByFileId(fileId);
        if (session == null || session.multipart()) {
            throw new ImException(ImErrorCode.NOT_FOUND, "upload session not found");
        }
        return complete(session, userId);
    }

    public FileUploadCompleteResult uploadSingleFile(String userId, String fileName, String contentType,
                                                     byte[] data, String hash, String fileGroup) {
        validateUser(userId);
        if (data == null || data.length == 0) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "file body is empty");
        }
        if (data.length > maxProxyUploadBytes) {
            throw new ImException(ImErrorCode.BAD_REQUEST,
                    "file too large: " + data.length + " (max " + maxProxyUploadBytes + ")");
        }
        validateFile(fileName, data.length);
        String fileId = IdGenerator.fileId();
        String objectKey = objectKey(fileId, fileName);
        String mimeType = normalizeContentType(contentType);
        fileStorage.upload(bucket, objectKey, data, mimeType);
        String fileUrl = fileStorage.presignGetObject(bucket, objectKey, presignExpiresSeconds);
        FileObjectMetadata metadata = new FileObjectMetadata(fileId, userId, bucket, objectKey, fileName,
                data.length, mimeType, hash != null ? hash : "", DEFAULT_ENGINE,
                normalizeFileGroup(fileGroup), System.currentTimeMillis());
        metadataStore.save(metadata);
        log.info(StructuredLog.event(LogEvents.FILE_UPLOAD_COMPLETED,
                LogFields.USER_ID, userId,
                LogFields.FILE_ID, fileId,
                LogFields.FILE_SIZE, data.length,
                LogFields.FILE_GROUP, metadata.fileGroup()));
        return new FileUploadCompleteResult(fileUrl, fileId, objectKey, fileName, mimeType, data.length);
    }

    public MultipartSignResult initiateMultipartUpload(String userId, String fileName, long fileSize,
                                                       String contentType, String hash, String fileGroup) {
        validateUser(userId);
        validateFile(fileName, fileSize);
        String fileId = IdGenerator.fileId();
        String objectKey = objectKey(fileId, fileName);
        String uploadId = fileStorage.initiateMultipartUpload(bucket, objectKey);
        UploadSession session = new UploadSession(fileId, uploadId, bucket, objectKey, userId, fileName,
                fileSize, normalizeContentType(contentType), hash, normalizeFileGroup(fileGroup), true);
        uploadSessionStore.save(session);
        log.info(StructuredLog.event(LogEvents.FILE_UPLOAD_SIGNED,
                LogFields.USER_ID, userId,
                LogFields.FILE_ID, fileId,
                LogFields.UPLOAD_ID, uploadId,
                LogFields.FILE_SIZE, fileSize,
                LogFields.FILE_GROUP, session.fileGroup()));
        return new MultipartSignResult(fileId, objectKey, uploadId, presignExpiresSeconds);
    }

    public PresignedPartResult signMultipartPart(String userId, String uploadId, int partNumber) {
        validateUser(userId);
        if (uploadId == null || uploadId.isBlank() || partNumber < 1 || partNumber > 10_000) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "invalid multipart part request");
        }
        UploadSession session = uploadSessionStore.getByUploadId(uploadId);
        if (session == null || !session.multipart()) {
            throw new ImException(ImErrorCode.NOT_FOUND, "upload session not found");
        }
        ensureOwner(session, userId);
        String uploadUrl = fileStorage.presignUploadPart(session.bucket(), session.objectKey(), uploadId,
                partNumber, presignExpiresSeconds);
        log.info(StructuredLog.event(LogEvents.FILE_UPLOAD_SIGNED,
                LogFields.USER_ID, userId,
                LogFields.FILE_ID, session.fileId(),
                LogFields.UPLOAD_ID, uploadId,
                LogFields.PART_NUMBER, partNumber,
                LogFields.FILE_GROUP, session.fileGroup()));
        return new PresignedPartResult(uploadId, partNumber, uploadUrl, "PUT", Map.of(), presignExpiresSeconds);
    }

    public String uploadMultipartPart(String userId, String uploadId, int partNumber, byte[] data) {
        validateUser(userId);
        if (uploadId == null || uploadId.isBlank() || partNumber < 1 || partNumber > 10_000 ||
                data == null || data.length == 0) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "uploadId, partNumber and body are required");
        }
        UploadSession session = uploadSessionStore.getByUploadId(uploadId);
        if (session == null || !session.multipart()) {
            throw new ImException(ImErrorCode.NOT_FOUND, "upload session not found");
        }
        ensureOwner(session, userId);
        if (data.length > maxProxyUploadBytes || data.length > session.fileSize()) {
            throw new ImException(ImErrorCode.BAD_REQUEST,
                    "multipart part too large: " + data.length + " (fileSize " + session.fileSize() +
                            ", max " + maxProxyUploadBytes + ")");
        }
        String etag = fileStorage.uploadPart(session.bucket(), session.objectKey(), uploadId, partNumber, data);
        log.info(StructuredLog.event(LogEvents.FILE_MULTIPART_PART_UPLOADED,
                LogFields.USER_ID, userId,
                LogFields.FILE_ID, session.fileId(),
                LogFields.UPLOAD_ID, uploadId,
                LogFields.PART_NUMBER, partNumber,
                LogFields.PART_SIZE, data.length,
                LogFields.FILE_GROUP, session.fileGroup()));
        return etag;
    }

    public FileUploadCompleteResult completeMultipartUpload(String userId, String uploadId, List<PartInfo> parts) {
        validateUser(userId);
        if (uploadId == null || uploadId.isBlank() || parts == null || parts.isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "uploadId and parts are required");
        }
        UploadSession session = uploadSessionStore.getByUploadId(uploadId);
        if (session == null || !session.multipart()) {
            throw new ImException(ImErrorCode.NOT_FOUND, "upload session not found");
        }
        ensureOwner(session, userId);
        List<PartInfo> sorted = parts.stream()
                .sorted((a, b) -> Integer.compare(a.partNumber(), b.partNumber()))
                .toList();
        fileStorage.completeMultipartUpload(session.bucket(), session.objectKey(), uploadId, sorted);
        return complete(session, userId);
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
        if (!fileStorage.exists(session.bucket(), session.objectKey())) {
            throw new ImException(ImErrorCode.NOT_FOUND, "uploaded object not found");
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
        return new FileUploadCompleteResult(fileStorage.presignGetObject(
                session.bucket(), session.objectKey(), presignExpiresSeconds),
                session.fileId(), session.objectKey(), session.fileName(), session.contentType(), session.fileSize());
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
        if (fileSize > maxProxyUploadBytes) {
            throw new ImException(ImErrorCode.BAD_REQUEST,
                    "file too large: " + fileSize + " (max " + maxProxyUploadBytes + ")");
        }
    }

    private static void ensureOwner(UploadSession session, String userId) {
        if (!session.userId().equals(userId)) {
            throw new ImException(ImErrorCode.FORBIDDEN, "file upload does not belong to current user");
        }
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

    private static String normalizeFileGroup(String fileGroup) {
        return fileGroup != null && !fileGroup.isBlank() ? fileGroup : "file";
    }
}

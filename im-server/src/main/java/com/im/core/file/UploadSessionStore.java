package com.im.core.file;

public interface UploadSessionStore {

    void save(UploadSession session);

    UploadSession getByFileId(String fileId);

    UploadSession getByUploadId(String uploadId);

    void delete(UploadSession session);
}

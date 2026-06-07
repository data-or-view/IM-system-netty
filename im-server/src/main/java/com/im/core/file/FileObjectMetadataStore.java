package com.im.core.file;

public interface FileObjectMetadataStore {

    void save(FileObjectMetadata metadata);

    FileObjectMetadata findByFileId(String fileId);
}

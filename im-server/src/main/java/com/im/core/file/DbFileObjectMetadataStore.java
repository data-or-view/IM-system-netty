package com.im.core.file;

import com.im.common.exception.PersistenceExceptions;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.entity.ObjectEntity;
import com.im.core.db.mapper.ObjectMapper;
import org.apache.ibatis.session.SqlSession;

public class DbFileObjectMetadataStore implements FileObjectMetadataStore {

    private final String bucket;

    public DbFileObjectMetadataStore(String bucket) {
        this.bucket = bucket;
    }

    @Override
    public void save(FileObjectMetadata metadata) {
        PersistenceExceptions.runDatabase("save file object metadata", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ObjectMapper mapper = session.getMapper(ObjectMapper.class);
                ObjectEntity existing = mapper.selectByFileId(metadata.fileId());
                ObjectEntity entity = existing != null ? existing : new ObjectEntity();
                entity.setName(metadata.fileId());
                entity.setUserId(metadata.userId());
                entity.setHash(metadata.hash() != null ? metadata.hash() : "");
                entity.setEngine(metadata.engine() != null ? metadata.engine() : "");
                entity.setBucket(metadata.bucket() != null && !metadata.bucket().isBlank() ? metadata.bucket() : bucket);
                entity.setObjectKey(metadata.objectKey());
                entity.setFileSize(metadata.fileSize());
                entity.setContentType(metadata.contentType() != null ? metadata.contentType() : "");
                entity.setFileGroup(metadata.fileGroup() != null ? metadata.fileGroup() : "");
                entity.setEx(metadata.fileName() != null ? metadata.fileName() : "");
                entity.setCreatedAt(metadata.createdAt());
                if (existing == null) {
                    mapper.insert(entity);
                } else {
                    mapper.updateById(entity);
                }
                session.commit();
            }
            return null;
        });
    }

    @Override
    public FileObjectMetadata findByFileId(String fileId) {
        return PersistenceExceptions.runDatabase("get file object metadata", () -> {
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                ObjectEntity entity = session.getMapper(ObjectMapper.class).selectByFileId(fileId);
                if (entity == null) {
                    return null;
                }
                return new FileObjectMetadata(
                        entity.getName(),
                        entity.getUserId(),
                        entity.getBucket() != null && !entity.getBucket().isBlank() ? entity.getBucket() : bucket,
                        entity.getObjectKey(),
                        entity.getEx(),
                        entity.getFileSize(),
                        entity.getContentType(),
                        entity.getHash(),
                        entity.getEngine(),
                        entity.getFileGroup(),
                        entity.getCreatedAt());
            }
        });
    }
}

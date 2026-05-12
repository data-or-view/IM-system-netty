package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 文件上传元数据实体，映射 {@code im_objects} 表。
 *
 * <p>对应 OpenIM {@code model.Object}。</p>
 *
 * <p>上传流程：上传前先查 hash，如果已存在则跳过上传直接返回已有 URL。</p>
 * <pre>
 * 小文件：客户端 → 后端 → IFileStorage.upload() → MinIO → 记录 Object
 * 大文件：客户端 → 获取预签名 → 直传 MinIO → CompleteUpload → 记录 Object
 * </pre>
 */
@TableName("im_objects")
public class ObjectEntity {

    @TableField("id")
    private Long id;

    @TableField("name")
    private String name;

    @TableField("user_id")
    private String userId;

    @TableField("hash")
    private String hash;

    @TableField("engine")
    private String engine;

    @TableField("object_key")
    private String objectKey;

    @TableField("file_size")
    private long fileSize;

    @TableField("content_type")
    private String contentType;

    @TableField("file_group")
    private String fileGroup;

    @TableField("ex")
    private String ex;

    @TableField("created_at")
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getFileGroup() { return fileGroup; }
    public void setFileGroup(String fileGroup) { this.fileGroup = fileGroup; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}

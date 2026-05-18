package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 增量同步变更日志，映射 {@code im_sync_changes} 表。
 *
 * <p>每次数据变更（好友增删、群进退、成员变更、会话更新等）时追加一条记录。
 * 客户端通过 version 拉取所有大于已知版本的变更。</p>
 */
@TableName("im_sync_changes")
public class SyncChangeEntity {

    @TableId
    @TableField("id")
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("entity_type")
    private String entityType;

    @TableField("entity_id")
    private String entityId;

    @TableField("version")
    private long version;

    @TableField("action")
    private String action;

    @TableField("created_at")
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}

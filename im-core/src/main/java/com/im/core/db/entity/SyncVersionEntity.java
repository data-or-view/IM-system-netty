package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 增量同步版本计数器，映射 {@code im_sync_versions} 表。
 *
 * <p>每行记录一个 (user_id, entity_type) 的当前最新版本号。
 * 每次数据变更时原子递增 version，用于增量同步的版本对比。</p>
 */
@TableName("im_sync_versions")
public class SyncVersionEntity {

    @TableId
    @TableField("user_id")
    private String userId;

    @TableField("entity_type")
    private String entityType;

    @TableField("version")
    private long version;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

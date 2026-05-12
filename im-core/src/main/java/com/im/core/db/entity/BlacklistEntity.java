package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 黑名单实体，映射 {@code im_blacklist} 表。
 *
 * <p>对应 OpenIM {@code model.Black}：拉黑关系表。</p>
 *
 * <p>逻辑：拉黑后，被拉黑者发的消息不投递，但不通知被拉黑者。</p>
 */
@TableName("im_blacklist")
public class BlacklistEntity {

    @TableField("id")
    private Long id;

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("block_user_id")
    private String blockUserId;

    @TableField("add_source")
    private int addSource;

    @TableField("operator_user_id")
    private String operatorUserId;

    @TableField("ex")
    private String ex;

    @TableField("created_at")
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getBlockUserId() { return blockUserId; }
    public void setBlockUserId(String blockUserId) { this.blockUserId = blockUserId; }

    public int getAddSource() { return addSource; }
    public void setAddSource(int addSource) { this.addSource = addSource; }

    public String getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(String operatorUserId) { this.operatorUserId = operatorUserId; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}

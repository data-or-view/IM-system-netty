package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 好友关系实体，映射 {@code im_friends} 表。
 *
 * <p>对应 OpenIM {@code model.Friend}：记录谁和谁是好友，含备注、来源、置顶。</p>
 */
@TableName("im_friends")
public class FriendEntity {

    @TableId
    @TableField("id")
    private Long id;

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("friend_user_id")
    private String friendUserId;

    @TableField("remark")
    private String remark;

    @TableField("add_source")
    private int addSource;

    @TableField("operator_user_id")
    private String operatorUserId;

    @TableField("ex")
    private String ex;

    @TableField("is_pinned")
    private int isPinned;

    @TableField("created_at")
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getFriendUserId() { return friendUserId; }
    public void setFriendUserId(String friendUserId) { this.friendUserId = friendUserId; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public int getAddSource() { return addSource; }
    public void setAddSource(int addSource) { this.addSource = addSource; }

    public String getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(String operatorUserId) { this.operatorUserId = operatorUserId; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public int getIsPinned() { return isPinned; }
    public void setIsPinned(int isPinned) { this.isPinned = isPinned; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}

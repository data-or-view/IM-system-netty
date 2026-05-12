package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 群组实体，映射 {@code im_groups} 表。
 *
 * <p>对应 OpenIM {@code model.Group}：群组基本信息 + 权限配置。</p>
 */
@TableName("im_groups")
public class GroupEntity {

    @TableField("group_id")
    private String groupId;

    @TableField("group_name")
    private String groupName;

    @TableField("notification")
    private String notification;

    @TableField("introduction")
    private String introduction;

    @TableField("face_url")
    private String faceUrl;

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("member_count")
    private int memberCount;

    @TableField("status")
    private int status;

    @TableField("group_type")
    private int groupType;

    @TableField("need_verification")
    private int needVerification;

    @TableField("look_member_info")
    private int lookMemberInfo;

    @TableField("apply_member_friend")
    private int applyMemberFriend;

    @TableField("notification_user_id")
    private String notificationUserId;

    @TableField("notification_time")
    private long notificationUpdateTime;

    @TableField("ex")
    private String ex;

    @TableField("created_at")
    private long createdAt;

    @TableField("updated_at")
    private long updatedAt;

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getNotification() { return notification; }
    public void setNotification(String notification) { this.notification = notification; }

    public String getIntroduction() { return introduction; }
    public void setIntroduction(String introduction) { this.introduction = introduction; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public int getGroupType() { return groupType; }
    public void setGroupType(int groupType) { this.groupType = groupType; }

    public int getNeedVerification() { return needVerification; }
    public void setNeedVerification(int needVerification) { this.needVerification = needVerification; }

    public int getLookMemberInfo() { return lookMemberInfo; }
    public void setLookMemberInfo(int lookMemberInfo) { this.lookMemberInfo = lookMemberInfo; }

    public int getApplyMemberFriend() { return applyMemberFriend; }
    public void setApplyMemberFriend(int applyMemberFriend) { this.applyMemberFriend = applyMemberFriend; }

    public String getNotificationUserId() { return notificationUserId; }
    public void setNotificationUserId(String notificationUserId) { this.notificationUserId = notificationUserId; }

    public long getNotificationUpdateTime() { return notificationUpdateTime; }
    public void setNotificationUpdateTime(long notificationUpdateTime) { this.notificationUpdateTime = notificationUpdateTime; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}

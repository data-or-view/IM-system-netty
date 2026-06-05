package com.im.api;

/**
 * 群组基本信息。
 *
 * <p>对应 OpenIM {@code GetGroupsInfoResp.GroupInfo} / {@code CreateGroupResp}。</p>
 *
 * <p>含群组基本资料 + 权限配置字段。</p>
 */
public class GroupInformation {

    private String groupId;
    private String groupName;
    private String notification;
    private String introduction;
    private String faceUrl;
    private String ownerUserId;
    private int memberCount;
    private GroupStatus status = GroupStatus.NORMAL;
    private GroupType groupType = GroupType.PRIVATE;
    private GroupJoinVerification needVerification = GroupJoinVerification.DIRECT;
    private GroupMemberInfoVisibility lookMemberInfo = GroupMemberInfoVisibility.ALL_VISIBLE;
    private GroupMemberFriendPolicy applyMemberFriend = GroupMemberFriendPolicy.ALLOW;

    /** 最后更新公告的用户ID */
    private String notificationUserId;

    /** 公告更新时间 */
    private long notificationUpdateTime;

    /** 扩展字段（JSON） */
    private String ex;

    private long createTime;
    private long updateTime;

    public GroupInformation() {}

    public GroupInformation(String groupId, String groupName, String ownerUserId) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.ownerUserId = ownerUserId;
        this.memberCount = 1;
        this.status = GroupStatus.NORMAL;
        this.groupType = GroupType.PRIVATE;
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

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

    public GroupStatus getStatus() { return status; }
    public void setStatus(GroupStatus status) { this.status = status != null ? status : GroupStatus.NORMAL; }

    public GroupType getGroupType() { return groupType; }
    public void setGroupType(GroupType groupType) { this.groupType = groupType != null ? groupType : GroupType.PRIVATE; }

    public GroupJoinVerification getNeedVerification() { return needVerification; }
    public void setNeedVerification(GroupJoinVerification needVerification) {
        this.needVerification = needVerification != null ? needVerification : GroupJoinVerification.DIRECT;
    }

    public GroupMemberInfoVisibility getLookMemberInfo() { return lookMemberInfo; }
    public void setLookMemberInfo(GroupMemberInfoVisibility lookMemberInfo) {
        this.lookMemberInfo = lookMemberInfo != null ? lookMemberInfo : GroupMemberInfoVisibility.ALL_VISIBLE;
    }

    public GroupMemberFriendPolicy getApplyMemberFriend() { return applyMemberFriend; }
    public void setApplyMemberFriend(GroupMemberFriendPolicy applyMemberFriend) {
        this.applyMemberFriend = applyMemberFriend != null ? applyMemberFriend : GroupMemberFriendPolicy.ALLOW;
    }

    public String getNotificationUserId() { return notificationUserId; }
    public void setNotificationUserId(String notificationUserId) { this.notificationUserId = notificationUserId; }

    public long getNotificationUpdateTime() { return notificationUpdateTime; }
    public void setNotificationUpdateTime(long notificationUpdateTime) { this.notificationUpdateTime = notificationUpdateTime; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }
}

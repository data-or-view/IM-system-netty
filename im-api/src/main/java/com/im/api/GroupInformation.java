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
    private int status;

    /** 群类型: 0=私有群, 1=公开群 */
    private int groupType;

    /** 加群验证: 0=无条件入群, 1=需验证, 2=需邀请, 3=不允许 */
    private int needVerification;

    /** 成员信息可见: 0=所有人可见, 1=仅管理员 */
    private int lookMemberInfo;

    /** 允许互加好友: 0=允许, 1=不允许 */
    private int applyMemberFriend;

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
        this.status = 1;
        this.groupType = 0;
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

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }
}

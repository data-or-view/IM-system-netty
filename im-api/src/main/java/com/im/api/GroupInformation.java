package com.im.api;

/**
 * 群组基本信息。
 */
public class GroupInformation {

    private String groupId;
    private String groupName;
    private String notification;
    private String faceUrl;
    private String ownerUserId;
    private int memberCount;
    private int status;
    private long createTime;

    public GroupInformation() {}

    public GroupInformation(String groupId, String groupName, String ownerUserId) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.ownerUserId = ownerUserId;
        this.memberCount = 1;
        this.status = 1;
        this.createTime = System.currentTimeMillis();
    }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getNotification() { return notification; }
    public void setNotification(String notification) { this.notification = notification; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
}

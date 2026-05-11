package com.im.api;

/**
 * 好友关系信息。
 */
public class FriendInformation {

    private String ownerUserId;
    private String friendUserId;
    private String remark;
    private boolean isPinned;
    private long createTime;

    public FriendInformation() {}

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getFriendUserId() { return friendUserId; }
    public void setFriendUserId(String friendUserId) { this.friendUserId = friendUserId; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public boolean isPinned() { return isPinned; }
    public void setPinned(boolean pinned) { isPinned = pinned; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }
}

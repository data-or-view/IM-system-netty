package com.im.api;

/**
 * 好友关系信息。
 *
 * <p>对应 OpenIM {@code GetFriendListResp.FriendInfo}。</p>
 *
 * <p>每条记录表示 {@code ownerUserId} 的好友 {@code friendUserId}，
 * 含备注、来源、置顶等信息。</p>
 */
public class FriendInformation {

    private String ownerUserId;
    private String friendUserId;
    private String nickname;
    private String remark;
    private String faceUrl;

    private ApplySource addSource = ApplySource.UNKNOWN;

    /** 扩展字段（JSON） */
    private String ex;

    private boolean isPinned;
    private long createTime;

    /** 是否已删除（增量同步中用，标记删除的好友关系）。 */
    private boolean deleted;

    public FriendInformation() {}

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getFriendUserId() { return friendUserId; }
    public void setFriendUserId(String friendUserId) { this.friendUserId = friendUserId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public ApplySource getAddSource() { return addSource; }
    public void setAddSource(ApplySource addSource) {
        this.addSource = addSource != null ? addSource : ApplySource.UNKNOWN;
    }

    public void setAddSourceCode(int addSourceCode) {
        setAddSource(ApplySource.fromCode(addSourceCode));
    }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public boolean isPinned() { return isPinned; }
    public void setPinned(boolean pinned) { isPinned = pinned; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}

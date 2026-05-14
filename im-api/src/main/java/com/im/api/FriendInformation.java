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
    private String remark;
    private String faceUrl;

    /** 添加来源: 1=搜索, 2=二维码, 3=群添加 */
    private int addSource;

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

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public int getAddSource() { return addSource; }
    public void setAddSource(int addSource) { this.addSource = addSource; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public boolean isPinned() { return isPinned; }
    public void setPinned(boolean pinned) { isPinned = pinned; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}

package com.im.api;

/**
 * 群成员信息。
 *
 * <p>对应 OpenIM {@code GetGroupMemberListResp.GroupMemberFullInfo}。</p>
 *
 * <p>角色层级：1=普通成员, 100=管理员, 200=群主。</p>
 */
public class GroupMemberInformation {

    private String groupId;
    private String userId;
    private String nickname;
    private String faceUrl;

    /** 1=普通成员, 100=管理员, 200=群主 */
    private int roleLevel;

    /** 入群来源 */
    private int joinSource;

    /** 邀请人 ID */
    private String inviterUserId;

    /** 禁言截止时间(毫秒), 0=不禁言 */
    private long muteEndTime;

    /** 扩展字段 */
    private String ex;

    /** 入群时间 */
    private long joinedAt;

    public GroupMemberInformation() {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public int getRoleLevel() { return roleLevel; }
    public void setRoleLevel(int roleLevel) { this.roleLevel = roleLevel; }

    public int getJoinSource() { return joinSource; }
    public void setJoinSource(int joinSource) { this.joinSource = joinSource; }

    public String getInviterUserId() { return inviterUserId; }
    public void setInviterUserId(String inviterUserId) { this.inviterUserId = inviterUserId; }

    public long getMuteEndTime() { return muteEndTime; }
    public void setMuteEndTime(long muteEndTime) { this.muteEndTime = muteEndTime; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getJoinedAt() { return joinedAt; }
    public void setJoinedAt(long joinedAt) { this.joinedAt = joinedAt; }

    /** 是否被禁言 */
    public boolean isMuted() {
        return muteEndTime > 0 && muteEndTime > System.currentTimeMillis();
    }

    /** 是否为群主 */
    public boolean isOwner() {
        return roleLevel >= 200;
    }

    /** 是否为管理员或群主 */
    public boolean isAdminOrOwner() {
        return roleLevel >= 100;
    }
}

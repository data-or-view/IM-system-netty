package com.im.api;

/**
 * 加群申请记录。
 *
 * <p>对应 OpenIM {@code GetGroupApplicationListResp}。</p>
 *
 * <p>字段名与 DB Entity 对齐：Handled 过去式，无简写。</p>
 */
public class GroupApply {

    private String groupId;
    private String groupName;
    private String userId;
    private String userNickname;
    private String userFaceUrl;
    private String reqMsg;
    private String handledMsg;
    private String handlerUserId;
    private ApplyHandleResult handleResult = ApplyHandleResult.PENDING;
    private ApplySource joinSource = ApplySource.UNKNOWN;

    /** 邀请人 ID（被邀请入群时有效） */
    private String inviterUserId;

    private long createTime;
    private long handledTime;

    public GroupApply() {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserNickname() { return userNickname; }
    public void setUserNickname(String userNickname) { this.userNickname = userNickname; }

    public String getUserFaceUrl() { return userFaceUrl; }
    public void setUserFaceUrl(String userFaceUrl) { this.userFaceUrl = userFaceUrl; }

    public String getReqMsg() { return reqMsg; }
    public void setReqMsg(String reqMsg) { this.reqMsg = reqMsg; }

    public String getHandledMsg() { return handledMsg; }
    public void setHandledMsg(String handledMsg) { this.handledMsg = handledMsg; }

    public String getHandlerUserId() { return handlerUserId; }
    public void setHandlerUserId(String handlerUserId) { this.handlerUserId = handlerUserId; }

    public ApplyHandleResult getHandleResult() { return handleResult; }
    public void setHandleResult(ApplyHandleResult handleResult) {
        this.handleResult = handleResult != null ? handleResult : ApplyHandleResult.PENDING;
    }

    public ApplySource getJoinSource() { return joinSource; }
    public void setJoinSource(ApplySource joinSource) {
        this.joinSource = joinSource != null ? joinSource : ApplySource.UNKNOWN;
    }

    public String getInviterUserId() { return inviterUserId; }
    public void setInviterUserId(String inviterUserId) { this.inviterUserId = inviterUserId; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getHandledTime() { return handledTime; }
    public void setHandledTime(long handledTime) { this.handledTime = handledTime; }
}

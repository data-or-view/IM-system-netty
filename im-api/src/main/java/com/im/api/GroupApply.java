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
    private String userId;
    private String reqMsg;
    private String handledMsg;
    private String handlerUserId;

    /** 0=待处理, 1=已同意, 2=已拒绝 */
    private int handleResult;

    /** 来源: 搜索/二维码/邀请 */
    private int joinSource;

    /** 邀请人 ID（被邀请入群时有效） */
    private String inviterUserId;

    private long createTime;
    private long handledTime;

    public GroupApply() {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getReqMsg() { return reqMsg; }
    public void setReqMsg(String reqMsg) { this.reqMsg = reqMsg; }

    public String getHandledMsg() { return handledMsg; }
    public void setHandledMsg(String handledMsg) { this.handledMsg = handledMsg; }

    public String getHandlerUserId() { return handlerUserId; }
    public void setHandlerUserId(String handlerUserId) { this.handlerUserId = handlerUserId; }

    public int getHandleResult() { return handleResult; }
    public void setHandleResult(int handleResult) { this.handleResult = handleResult; }

    public int getJoinSource() { return joinSource; }
    public void setJoinSource(int joinSource) { this.joinSource = joinSource; }

    public String getInviterUserId() { return inviterUserId; }
    public void setInviterUserId(String inviterUserId) { this.inviterUserId = inviterUserId; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getHandledTime() { return handledTime; }
    public void setHandledTime(long handledTime) { this.handledTime = handledTime; }
}

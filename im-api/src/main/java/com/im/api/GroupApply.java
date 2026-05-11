package com.im.api;

/**
 * 加群申请记录。
 */
public class GroupApply {

    private String groupId;
    private String userId;
    private String reqMsg;
    private String handleMsg;
    /** 0=待处理, 1=已同意, 2=已拒绝 */
    private int handleResult;
    private String handleUserId;
    private long createTime;
    private long handleTime;

    public GroupApply() {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getReqMsg() { return reqMsg; }
    public void setReqMsg(String reqMsg) { this.reqMsg = reqMsg; }

    public String getHandleMsg() { return handleMsg; }
    public void setHandleMsg(String handleMsg) { this.handleMsg = handleMsg; }

    public int getHandleResult() { return handleResult; }
    public void setHandleResult(int handleResult) { this.handleResult = handleResult; }

    public String getHandleUserId() { return handleUserId; }
    public void setHandleUserId(String handleUserId) { this.handleUserId = handleUserId; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getHandleTime() { return handleTime; }
    public void setHandleTime(long handleTime) { this.handleTime = handleTime; }
}

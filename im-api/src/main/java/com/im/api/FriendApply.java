package com.im.api;

/**
 * 好友申请记录。
 */
public class FriendApply {

    private String fromUserId;
    private String toUserId;
    private String reqMsg;
    private String handleMsg;
    /** 0=待处理, 1=已同意, 2=已拒绝 */
    private int handleResult;
    private long createTime;
    private long handleTime;

    public FriendApply() {}

    public String getFromUserId() { return fromUserId; }
    public void setFromUserId(String fromUserId) { this.fromUserId = fromUserId; }

    public String getToUserId() { return toUserId; }
    public void setToUserId(String toUserId) { this.toUserId = toUserId; }

    public String getReqMsg() { return reqMsg; }
    public void setReqMsg(String reqMsg) { this.reqMsg = reqMsg; }

    public String getHandleMsg() { return handleMsg; }
    public void setHandleMsg(String handleMsg) { this.handleMsg = handleMsg; }

    public int getHandleResult() { return handleResult; }
    public void setHandleResult(int handleResult) { this.handleResult = handleResult; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getHandleTime() { return handleTime; }
    public void setHandleTime(long handleTime) { this.handleTime = handleTime; }
}

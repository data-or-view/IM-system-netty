package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 好友申请实体，映射 {@code im_friend_requests} 表。
 *
 * <p>对应 OpenIM {@code model.FriendRequest}：好友申请的完整审批链路——申请、处理、时间线。</p>
 */
@TableName("im_friend_requests")
public class FriendRequestEntity {

    @TableField("id")
    private Long id;

    @TableField("from_user_id")
    private String fromUserId;

    @TableField("to_user_id")
    private String toUserId;

    @TableField("handle_result")
    private int handleResult;

    @TableField("req_msg")
    private String reqMsg;

    @TableField("handler_user_id")
    private String handlerUserId;

    @TableField("handle_msg")
    private String handleMsg;

    @TableField("handle_time")
    private long handleTime;

    @TableField("ex")
    private String ex;

    @TableField("created_at")
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFromUserId() { return fromUserId; }
    public void setFromUserId(String fromUserId) { this.fromUserId = fromUserId; }

    public String getToUserId() { return toUserId; }
    public void setToUserId(String toUserId) { this.toUserId = toUserId; }

    public int getHandleResult() { return handleResult; }
    public void setHandleResult(int handleResult) { this.handleResult = handleResult; }

    public String getReqMsg() { return reqMsg; }
    public void setReqMsg(String reqMsg) { this.reqMsg = reqMsg; }

    public String getHandlerUserId() { return handlerUserId; }
    public void setHandlerUserId(String handlerUserId) { this.handlerUserId = handlerUserId; }

    public String getHandleMsg() { return handleMsg; }
    public void setHandleMsg(String handleMsg) { this.handleMsg = handleMsg; }

    public long getHandleTime() { return handleTime; }
    public void setHandleTime(long handleTime) { this.handleTime = handleTime; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}

package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 加群申请实体，映射 {@code im_group_requests} 表。
 *
 * <p>对应 OpenIM {@code model.GroupRequest}：加群申请的完整审批链路。</p>
 */
@TableName("im_group_requests")
public class GroupRequestEntity {

    @TableField("id")
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("group_id")
    private String groupId;

    @TableField("handle_result")
    private int handleResult;

    @TableField("req_msg")
    private String reqMsg;

    @TableField("handled_msg")
    private String handledMsg;

    @TableField("handler_user_id")
    private String handlerUserId;

    @TableField("handled_time")
    private long handledTime;

    @TableField("join_source")
    private int joinSource;

    @TableField("inviter_user_id")
    private String inviterUserId;

    @TableField("ex")
    private String ex;

    @TableField("created_at")
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public int getHandleResult() { return handleResult; }
    public void setHandleResult(int handleResult) { this.handleResult = handleResult; }

    public String getReqMsg() { return reqMsg; }
    public void setReqMsg(String reqMsg) { this.reqMsg = reqMsg; }

    public String getHandledMsg() { return handledMsg; }
    public void setHandledMsg(String handledMsg) { this.handledMsg = handledMsg; }

    public String getHandlerUserId() { return handlerUserId; }
    public void setHandlerUserId(String handlerUserId) { this.handlerUserId = handlerUserId; }

    public long getHandledTime() { return handledTime; }
    public void setHandledTime(long handledTime) { this.handledTime = handledTime; }

    public int getJoinSource() { return joinSource; }
    public void setJoinSource(int joinSource) { this.joinSource = joinSource; }

    public String getInviterUserId() { return inviterUserId; }
    public void setInviterUserId(String inviterUserId) { this.inviterUserId = inviterUserId; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}

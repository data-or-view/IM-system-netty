package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户级消息可见性，映射 {@code im_message_visibility} 表。
 */
@TableName("im_message_visibility")
public class MessageVisibilityEntity {

    @TableField("id")
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("seq")
    private long seq;

    @TableField("client_msg_id")
    private String clientMsgId;

    @TableField("visibility_state")
    private int visibilityState;

    @TableField("operator_user_id")
    private String operatorUserId;

    @TableField("reason")
    private String reason;

    @TableField("updated_at")
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }

    public String getClientMsgId() { return clientMsgId; }
    public void setClientMsgId(String clientMsgId) { this.clientMsgId = clientMsgId; }

    public int getVisibilityState() { return visibilityState; }
    public void setVisibilityState(int visibilityState) { this.visibilityState = visibilityState; }

    public String getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(String operatorUserId) { this.operatorUserId = operatorUserId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}

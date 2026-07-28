package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** Durable inbound event used to de-duplicate conversation projection. */
@TableName("im_conversation_projection_events")
public class ConversationProjectionEventEntity {

    @TableField("owner_user_id")
    private String ownerUserId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("message_id")
    private String messageId;

    @TableField("message_seq")
    private long messageSeq;

    @TableField("created_at")
    private long createdAt;

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public long getMessageSeq() { return messageSeq; }
    public void setMessageSeq(long messageSeq) { this.messageSeq = messageSeq; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}

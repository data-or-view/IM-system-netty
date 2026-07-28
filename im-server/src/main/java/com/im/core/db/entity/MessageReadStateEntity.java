package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户级消息已读/投递状态，映射 {@code im_message_read_states} 表。
 */
@TableName("im_message_read_states")
public class MessageReadStateEntity {

    @TableField("id")
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("read_seq")
    private long readSeq;

    @TableField("pending_read_seq")
    private long pendingReadSeq;

    @TableField("delivered_seq")
    private long deliveredSeq;

    @TableField("unread_count")
    private int unreadCount;

    @TableField("updated_at")
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public long getReadSeq() { return readSeq; }
    public void setReadSeq(long readSeq) { this.readSeq = readSeq; }

    public long getPendingReadSeq() { return pendingReadSeq; }
    public void setPendingReadSeq(long pendingReadSeq) { this.pendingReadSeq = pendingReadSeq; }

    public long getDeliveredSeq() { return deliveredSeq; }
    public void setDeliveredSeq(long deliveredSeq) { this.deliveredSeq = deliveredSeq; }

    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}

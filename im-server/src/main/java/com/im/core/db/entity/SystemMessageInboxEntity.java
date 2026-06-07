package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("im_system_message_inbox")
public class SystemMessageInboxEntity {
    @TableField("id")
    private Long id;
    @TableField("message_id")
    private String messageId;
    @TableField("user_id")
    private String userId;
    @TableField("channel_id")
    private String channelId;
    @TableField("read_at")
    private long readAt;
    @TableField("deleted")
    private int deleted;
    @TableField("archived")
    private int archived;
    @TableField("created_at")
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public long getReadAt() { return readAt; }
    public void setReadAt(long readAt) { this.readAt = readAt; }
    public int getDeleted() { return deleted; }
    public void setDeleted(int deleted) { this.deleted = deleted; }
    public int getArchived() { return archived; }
    public void setArchived(int archived) { this.archived = archived; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}

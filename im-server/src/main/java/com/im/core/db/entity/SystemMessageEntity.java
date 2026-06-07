package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("im_system_messages")
public class SystemMessageEntity {

    @TableField("id")
    private Long id;
    @TableField("message_id")
    private String messageId;
    @TableField("channel_id")
    private String channelId;
    @TableField("title")
    private String title;
    @TableField("summary")
    private String summary;
    @TableField("content")
    private String content;
    @TableField("content_type")
    private String contentType;
    @TableField("sender_type")
    private String senderType;
    @TableField("sender_id")
    private String senderId;
    @TableField("priority")
    private int priority;
    @TableField("send_scope")
    private String sendScope;
    @TableField("created_at")
    private long createdAt;
    @TableField("expire_at")
    private long expireAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getSenderType() { return senderType; }
    public void setSenderType(String senderType) { this.senderType = senderType; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getSendScope() { return sendScope; }
    public void setSendScope(String sendScope) { this.sendScope = sendScope; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getExpireAt() { return expireAt; }
    public void setExpireAt(long expireAt) { this.expireAt = expireAt; }
}

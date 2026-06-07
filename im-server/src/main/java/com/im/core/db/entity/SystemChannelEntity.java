package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("im_system_channels")
public class SystemChannelEntity {

    @TableField("id")
    private Long id;
    @TableField("channel_id")
    private String channelId;
    @TableField("channel_name")
    private String channelName;
    @TableField("channel_type")
    private String channelType;
    @TableField("description")
    private String description;
    @TableField("status")
    private int status;
    @TableField("created_at")
    private long createdAt;
    @TableField("updated_at")
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}

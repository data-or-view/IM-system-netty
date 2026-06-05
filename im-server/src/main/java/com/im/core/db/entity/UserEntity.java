package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户实体，映射 {@code im_users} 表。
 *
 * <p>对应 OpenIM {@code model.User}：用户基本信息 + 全局消息接收选项。</p>
 */
@TableName("im_users")
public class UserEntity {

    @TableId
    @TableField("user_id")
    private String userId;

    @TableField("nickname")
    private String nickname;

    @TableField("face_url")
    private String faceUrl;

    @TableField("ex")
    private String ex;

    @TableField("app_manger_level")
    private int appMangerLevel;

    @TableField("global_recv_msg_opt")
    private int globalRecvMsgOpt;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("status")
    private int status;

    @TableField("created_at")
    private long createdAt;

    @TableField("updated_at")
    private long updatedAt;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public int getAppMangerLevel() { return appMangerLevel; }
    public void setAppMangerLevel(int appMangerLevel) { this.appMangerLevel = appMangerLevel; }

    public int getGlobalRecvMsgOpt() { return globalRecvMsgOpt; }
    public void setGlobalRecvMsgOpt(int globalRecvMsgOpt) { this.globalRecvMsgOpt = globalRecvMsgOpt; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}

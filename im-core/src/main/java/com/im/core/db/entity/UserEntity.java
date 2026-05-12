package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户实体（示例：映射 {@code im_users} 表）。
 *
 * <p>后续以此模式扩展：MessageEntity、ConversationEntity、GroupEntity、FriendEntity 等。</p>
 *
 * <pre>
 *     // 构造示例
 *     UserEntity user = new UserEntity();
 *     user.setUserId("alice");
 *     user.setNickname("Alice");
 *
 *     // 使用 Mapper
 *     userMapper.insert(user);
 * </pre>
 *
 * @see com.im.core.db.mapper.UserMapper
 */
@TableName("im_users")
public class UserEntity {

    @TableField("user_id")
    private String userId;

    @TableField("nickname")
    private String nickname;

    @TableField("face_url")
    private String faceUrl;

    @TableField("status")
    private int status;

    @TableField("created_at")
    private long createdAt;

    @TableField("updated_at")
    private long updatedAt;

    // ── getters / setters ──

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}

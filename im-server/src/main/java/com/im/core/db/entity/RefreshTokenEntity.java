package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("im_refresh_tokens")
public class RefreshTokenEntity {

    @TableId
    @TableField("token_id")
    private String tokenId;

    @TableField("user_id")
    private String userId;

    @TableField("token_hash")
    private String tokenHash;

    @TableField("app_manger_level")
    private int appMangerLevel;

    @TableField("issued_at")
    private long issuedAt;

    @TableField("expires_at")
    private long expiresAt;

    @TableField("revoked_at")
    private long revokedAt;

    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public int getAppMangerLevel() { return appMangerLevel; }
    public void setAppMangerLevel(int appMangerLevel) { this.appMangerLevel = appMangerLevel; }

    public long getIssuedAt() { return issuedAt; }
    public void setIssuedAt(long issuedAt) { this.issuedAt = issuedAt; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public long getRevokedAt() { return revokedAt; }
    public void setRevokedAt(long revokedAt) { this.revokedAt = revokedAt; }
}

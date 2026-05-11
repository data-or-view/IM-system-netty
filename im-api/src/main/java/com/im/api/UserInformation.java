package com.im.api;

import java.util.Objects;

/**
 * 用户公开信息。
 */
public class UserInformation {

    private String userId;
    private String nickname;
    private String faceUrl;
    private String ex;
    private long createTime;

    public UserInformation() {}

    public UserInformation(String userId, String nickname) {
        this.userId = userId;
        this.nickname = nickname;
        this.createTime = System.currentTimeMillis();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserInformation userInformation)) return false;
        return Objects.equals(userId, userInformation.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}

package com.im.api;

import java.util.Objects;

/**
 * 用户公开信息。
 *
 * <p>对应 OpenIM {@code GetSelfUserInfoResp} / {@code GetUsersInfoResp}。</p>
 *
 * <p>注意：{@code globalRecvMsgOpt}、{@code appMangerLevel}、{@code ex} 是完整字段，
 * 客户端可根据用户身份/权限展示不同的 UI 元素。</p>
 */
public class UserInformation {

    private String userId;
    private String nickname;
    private String faceUrl;
    private String ex;

    /** 管理员级别: 0=普通, 1=管理员, 2=超管 */
    private int appMangerLevel;

    /** 全局消息接收: 0=正常, 1=免打扰, 2=不接收 */
    private int globalRecvMsgOpt;

    private long createTime;
    private long updatedAt;

    public UserInformation() {}

    public UserInformation(String userId, String nickname) {
        this.userId = userId;
        this.nickname = nickname;
        this.createTime = System.currentTimeMillis();
        this.updatedAt = this.createTime;
    }

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

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

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

package com.im.api;

import java.util.Objects;

/**
 * 会话数据（conversation）。
 *
 * 对应 OpenIM 的 Conversation 结构（tools/conversation）：
 *   每个用户 uid + conversationID 唯一确定一条会话记录。
 *   用户在客户端看到的"聊天列表"就是按 lastMsgTime 排序的 Conversation 列表。
 *
 * 生命周期：
 *   · 第一次收到陌生用户的消息 → 自动创建 Conversation
 *   · 收到新消息 → 更新 lastMsgSeq / lastMsgTime / lastMsgContent + unreadCount++
 *   · 用户点开聊天 → 调用 markRead → unreadCount = 0
 *   · 用户长按置顶 → setPinned(true)
 */
public class Conversation {

    /** 单聊 */
    public static final int SESSION_TYPE_SINGLE = 1;
    /** 群聊 */
    public static final int SESSION_TYPE_GROUP = 2;

    /** 接收消息选项：正常 */
    public static final int RECV_OPT_NORMAL = 0;
    /** 接收消息选项：不提醒 */
    public static final int RECV_OPT_NOT_NOTIFY = 1;
    /** 接收消息选项：不接收 */
    public static final int RECV_OPT_NOT_RECEIVE = 2;

    private String conversationId;
    private String userId;
    private int sessionType;
    private String groupId;
    private long unreadCount;
    private String lastMsgContent;
    private String lastMsgId;
    private int lastMsgSeq;
    private long lastMsgTime;
    private boolean isPinned;
    private int recvMsgOpt;
    private long createTime;
    private long updateTime;

    public Conversation() {}

    public Conversation(String conversationId, String userId, int sessionType) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.sessionType = sessionType;
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    // ========== Getters / Setters ==========

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getSessionType() { return sessionType; }
    public void setSessionType(int sessionType) { this.sessionType = sessionType; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public long getUnreadCount() { return unreadCount; }
    public void setUnreadCount(long unreadCount) { this.unreadCount = unreadCount; }

    public String getLastMsgContent() { return lastMsgContent; }
    public void setLastMsgContent(String lastMsgContent) { this.lastMsgContent = lastMsgContent; }

    public String getLastMsgId() { return lastMsgId; }
    public void setLastMsgId(String lastMsgId) { this.lastMsgId = lastMsgId; }

    public int getLastMsgSeq() { return lastMsgSeq; }
    public void setLastMsgSeq(int lastMsgSeq) { this.lastMsgSeq = lastMsgSeq; }

    public long getLastMsgTime() { return lastMsgTime; }
    public void setLastMsgTime(long lastMsgTime) { this.lastMsgTime = lastMsgTime; }

    public boolean isPinned() { return isPinned; }
    public void setPinned(boolean pinned) { isPinned = pinned; }

    public int getRecvMsgOpt() { return recvMsgOpt; }
    public void setRecvMsgOpt(int recvMsgOpt) { this.recvMsgOpt = recvMsgOpt; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Conversation that)) return false;
        return Objects.equals(conversationId, that.conversationId)
                && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, userId);
    }
}

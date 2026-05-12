package com.im.api;

import java.util.Objects;

/**
 * 会话数据（conversation）。
 *
 * <p>对应 OpenIM {@code GetConversationsResp.Conversation}。</p>
 *
 * <p>核心设计：每个用户独立拥有一份会话视图。
 * {@code ownerUserId} 是会话所属者，{@code userId} 是对方用户（单聊时）。</p>
 *
 * <p>生命周期：</p>
 * <ul>
 *   <li>第一次收到消息 → 自动创建 Conversation</li>
 *   <li>收到新消息 → 更新 lastMsgSeq / lastMsgTime / lastMsgContent</li>
 *   <li>用户点开聊天 → 调用 markRead → unreadCount = 0</li>
 *   <li>用户操作设置 → isPinned / recvMsgOpt / burnDuration</li>
 * </ul>
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

    // ========== 会话标识 ==========

    /** 会话所属者（每个用户独立视图） */
    private String ownerUserId;

    /** 会话 ID（单聊: s_user1_user2, 群聊: g_groupId） */
    private String conversationId;

    /** 会话类型: 1=单聊, 2=群聊 */
    private int sessionType;

    /** 对方用户 ID（单聊时有效） */
    private String userId;

    /** 群组 ID（群聊时有效） */
    private String groupId;

    // ========== 展示字段（Manager 层聚合，非 DB 直存） ==========

    /** 未读消息数 */
    private long unreadCount;

    /** 最后一条消息内容（摘要展示） */
    private String lastMsgContent;

    /** 最后一条消息内容类型 */
    private int lastContentType;

    /** 最后一条消息 ID */
    private String lastMsgId;

    /** 最后一条消息 seq */
    private long lastMsgSeq;

    /** 最后一条消息时间 */
    private long lastMsgTime;

    // ========== 用户设置字段 ==========

    /** 是否置顶 */
    private boolean isPinned;

    /** 消息接收选项: 0=正常, 1=不提醒, 2=不接收 */
    private int recvMsgOpt;

    /** @类型: 0=未@, 1=@我, 2=@所有人 */
    private int groupAtType;

    /** 阅后即焚时长(秒), 0=不开启 */
    private int burnDuration;

    /** 是否开启消息自毁 */
    private boolean isMsgDestruct;

    /** 自毁时间(秒) */
    private int msgDestructTime;

    /** 是否私聊（仅互相可见） */
    private boolean isPrivateChat;

    /** 附加信息(JSON) */
    private String attachedInfo;

    /** 扩展字段(JSON) */
    private String ex;

    // ========== 时间线 ==========

    private long createTime;
    private long updateTime;

    public Conversation() {}

    public Conversation(String conversationId, String ownerUserId, int sessionType) {
        this.conversationId = conversationId;
        this.ownerUserId = ownerUserId;
        this.sessionType = sessionType;
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    // ========== Getters / Setters ==========

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public int getSessionType() { return sessionType; }
    public void setSessionType(int sessionType) { this.sessionType = sessionType; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public long getUnreadCount() { return unreadCount; }
    public void setUnreadCount(long unreadCount) { this.unreadCount = unreadCount; }

    public String getLastMsgContent() { return lastMsgContent; }
    public void setLastMsgContent(String lastMsgContent) { this.lastMsgContent = lastMsgContent; }

    public int getLastContentType() { return lastContentType; }
    public void setLastContentType(int lastContentType) { this.lastContentType = lastContentType; }

    public String getLastMsgId() { return lastMsgId; }
    public void setLastMsgId(String lastMsgId) { this.lastMsgId = lastMsgId; }

    public long getLastMsgSeq() { return lastMsgSeq; }
    public void setLastMsgSeq(long lastMsgSeq) { this.lastMsgSeq = lastMsgSeq; }

    public long getLastMsgTime() { return lastMsgTime; }
    public void setLastMsgTime(long lastMsgTime) { this.lastMsgTime = lastMsgTime; }

    public boolean isPinned() { return isPinned; }
    public void setPinned(boolean pinned) { isPinned = pinned; }

    public int getRecvMsgOpt() { return recvMsgOpt; }
    public void setRecvMsgOpt(int recvMsgOpt) { this.recvMsgOpt = recvMsgOpt; }

    public int getGroupAtType() { return groupAtType; }
    public void setGroupAtType(int groupAtType) { this.groupAtType = groupAtType; }

    public int getBurnDuration() { return burnDuration; }
    public void setBurnDuration(int burnDuration) { this.burnDuration = burnDuration; }

    public boolean isMsgDestruct() { return isMsgDestruct; }
    public void setMsgDestruct(boolean msgDestruct) { isMsgDestruct = msgDestruct; }

    public int getMsgDestructTime() { return msgDestructTime; }
    public void setMsgDestructTime(int msgDestructTime) { this.msgDestructTime = msgDestructTime; }

    public boolean isPrivateChat() { return isPrivateChat; }
    public void setPrivateChat(boolean privateChat) { isPrivateChat = privateChat; }

    public String getAttachedInfo() { return attachedInfo; }
    public void setAttachedInfo(String attachedInfo) { this.attachedInfo = attachedInfo; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Conversation that)) return false;
        return Objects.equals(conversationId, that.conversationId)
                && Objects.equals(ownerUserId, that.ownerUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, ownerUserId);
    }
}

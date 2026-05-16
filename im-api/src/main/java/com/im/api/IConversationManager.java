package com.im.api;

import java.util.List;

/**
 * 会话管理接口。
 *
 * <p>每个用户的"聊天列表"就是一条条 Conversation，按 lastMsgTime 排序。</p>
 *
 * <p>对应 OpenIM 的 conversation rpc：
 *   GetConversations → 获取用户所有会话
 *   GetOneConversation → 获取单个会话
 *   SetConversation → 更新会话设置（置顶/免打扰）
 *   UpdateConversationByMessage → 收到消息后更新 lastMsgSeq/unread
 * </p>
 */
public interface IConversationManager {

    /**
     * 获取用户的全部会话（按 lastMsgTime 降序）。
     * 无会话时返回空列表。
     *
     * @param ownerUserId 会话所属者（用户 ID）
     */
    List<Conversation> getConversations(String ownerUserId);

    /**
     * 获取单个会话。
     * 不存在时返回 null（客户端可自动创建）。
     *
     * @param ownerUserId    会话所属者
     * @param conversationId 会话 ID
     */
    Conversation getConversation(String ownerUserId, String conversationId);

    /**
     * 收到消息后更新会话状态。
     * 如果会话不存在则自动创建。
     *
     * @param ownerUserId    会话所属者
     * @param conversationId 会话 ID
     * @param msg            收到的消息
     * @param isSelf         是否为发送方自己（发送方不+1 unread）
     */
    void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf);

    /**
     * 用户已读消息，重置未读数。
     *
     * @param ownerUserId    用户 ID
     * @param conversationId 会话 ID
     * @param readSeq        已读到的 seq（<= 此 seq 的消息已读）
     */
    void markRead(String ownerUserId, String conversationId, long readSeq);

    /**
     * 设置置顶。
     */
    void setPinned(String ownerUserId, String conversationId, boolean pinned);

    /**
     * 设置消息接收选项。
     *
     * @param recvMsgOpt 0=正常, 1=不提醒, 2=不接收
     */
    void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt);

    /**
     * 设置阅后即焚时长。
     *
     * @param burnDuration 秒, 0=关闭
     */
    void setBurnDuration(String ownerUserId, String conversationId, int burnDuration);

    /**
     * 创建初始会话（发送第一条消息前调用）。
     *
     * @param ownerUserId    会话所属者
     * @param targetUserId   对方用户 ID（单聊）
     * @param conversationId 会话 ID
     */
    default void createSingleConversation(String ownerUserId, String targetUserId, String conversationId) {
        // 默认不实现
    }

    /**
     * 批量创建群聊会话（建群后调用）。
     *
     * @param memberIds      群成员 ID 列表
     * @param groupId        群 ID
     * @param conversationId 会话 ID（群聊用 group_ 前缀）
     */
    default void createGroupConversations(List<String> memberIds, String groupId, String conversationId) {
        // 默认不实现
    }

    // ========================================
    //  已读回执 + 未读计数
    // ========================================

    /**
     * 获取用户在指定会话的已读 seq。
     *
     * <p>小于等于该 seq 的消息均已读，大于的为未读。
     * 配合 {@link IMessageStore#getLastSeq} 可算出未读数。</p>
     *
     * @param ownerUserId    用户 ID
     * @param conversationId 会话 ID
     * @return 已读到的最大 seq，0=一条未读
     */
    default long getReadSeq(String ownerUserId, String conversationId) {
        throw new UnsupportedOperationException("getReadSeq not implemented");
    }

    /**
     * 获取用户所有会话的未读消息总数（应用首页角标）。
     */
    default int getTotalUnreadCount(String userId) {
        throw new UnsupportedOperationException("getTotalUnreadCount not implemented");
    }

    /**
     * 获取指定会话的未读消息数。
     */
    default int getUnreadCount(String ownerUserId, String conversationId) {
        throw new UnsupportedOperationException("getUnreadCount not implemented");
    }

    // ========================================
    //  会话增量同步
    // ========================================

    /**
     * 增量同步会话列表。
     *
     * <p>客户端传入上次同步的 version，服务端返回
     * 该 version 之后发生过变更（新建/置顶/免打扰/最后消息更新）的会话。</p>
     *
     * @param ownerUserId 用户 ID
     * @param version     客户端已知的最新 version
     * @return 增量同步结果
     */
    default IncrementalSyncResult<Conversation> getIncrementalConversations(String ownerUserId, long version) {
        throw new UnsupportedOperationException("getIncrementalConversations not implemented");
    }
}

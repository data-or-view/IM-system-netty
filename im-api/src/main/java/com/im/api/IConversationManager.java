package com.im.api;

import java.util.List;

/**
 * 会话管理接口。
 *
 * 每个用户的"聊天列表"就是一条条 Conversation，按 lastMsgTime 排序。
 *
 * 对应 OpenIM 的 conversationRpcClient：
 *   · GetConversations → 获取用户所有会话
 *   · GetOneConversation → 获取单个会话
 *   · SetConversation → 更新会话设置（置顶/免打扰）
 *   · UpdateConversationByMessage → 收到消息后更新 lastMsgSeq/unread
 */
public interface IConversationManager {

    /**
     * 获取用户的全部会话（按 lastMsgTime 降序）。
     * 无会话时返回空列表。
     */
    List<Conversation> getConversations(String userId);

    /**
     * 获取单个会话。
     * 不存在时返回 null（客户端可自动创建）。
     */
    Conversation getConversation(String userId, String conversationId);

    /**
     * 收到消息后更新会话状态。
     * 如果会话不存在则自动创建。
     *
     * @param conversationId 会话 ID
     * @param toUserId       消息接收方（哪个用户的对话被更新）
     * @param msg            收到的消息
     * @param isSelf         是否为发送方自己（发送方不+1 unread）
     */
    void updateOnMessage(String conversationId, String toUserId, IMCommand msg, boolean isSelf);

    /**
     * 用户已读消息，重置未读数。
     *
     * @param userId         用户 ID
     * @param conversationId 会话 ID
     * @param readSeq        已读到的 seq（<=这个 seq 的消息已读）
     */
    void markRead(String userId, String conversationId, int readSeq);

    /**
     * 设置置顶。
     */
    void setPinned(String userId, String conversationId, boolean pinned);

    /**
     * 设置消息接收选项。
     *
     * @param recvMsgOpt 0=正常, 1=不提醒, 2=不接收
     */
    void setRecvMsgOpt(String userId, String conversationId, int recvMsgOpt);
}

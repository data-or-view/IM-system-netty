package com.im.api;

import com.im.api.Message;

import java.util.List;

/**
 * 消息持久化存储。
 *
 * 职责：
 *   ① 保存消息（单聊/群聊/系统消息）
 *   ② 拉取离线消息（用户不在线时缓存的）
 *   ③ 按 conversation + seq 范围拉取历史消息（PullMessageBySeqList）
 *   ④ 标记已读 / 清理过期消息
 *
 * 实现选择：
 *   ┌──────────────┬─────────────────────────────────┐
 *   │ 场景         │ 实现                            │
 *   ├──────────────┼─────────────────────────────────┤
 *   │ 开发测试     │ LocalMessageStore (内存 List)   │
 *   │ 生产         │ DBMessageStore (MySQL/PostgreSQL)│
 *   │ 高性能       │ RocksDBMessageStore (本地 KV)   │
 *   └──────────────┴─────────────────────────────────┘
 */
public interface IMessageStore {

    /**
     * 保存消息（按 conversation 存储）。
     */
    void save(Message msg);

    /**
     * 批量保存。
     */
    default void saveAll(List<Message> messages) {
        messages.forEach(this::save);
    }

    /**
     * 拉取用户的离线消息。
     * 返回该用户不在线期间收到的所有消息。
     *
     * @param userId 目标用户
     * @param limit  最大条数
     * @return 离线消息列表
     */
    List<Message> pullOffline(String userId, int limit);

    /**
     * 按 conversation + seq 范围拉取消息（历史消息拉取）。
     * 对应 OpenIM 的 PullMessageBySeqs。
     *
     * @param conversationId 会话 ID
     * @param startSeq       起始 seq（含），0 表示从最早开始
     * @param endSeq         结束 seq（含），0 表示到最新
     * @param limit          最大返回条数
     * @return 消息列表，按 seq 升序排列
     */
    List<Message> pullBySequence(String conversationId, long startSeq, long endSeq, int limit);

    /**
     * 标记消息为已投递（从离线队列移除）。
     *
     * @param userId 目标用户
     * @param msgIds 已投递消息 ID 列表
     */
    void markDelivered(String userId, List<String> msgIds);

    /**
     * 删除用户早于某个 seqId 的消息（清理）。
     */
    default void deleteBefore(String userId, long seqId) {
        // 默认不实现
    }

    // ========================================
    //  消息删除 / 清空
    // ========================================

    /**
     * 软删除消息（标记已删除，对用户不可见）。
     *
     * <p>消息内容仍保存在服务端，用于审计/合规/数据恢复。
     * 接收方拉取消息时，已删除的消息不在结果中返回。</p>
     *
     * @param conversationId 会话 ID
     * @param msgIds         要删除的消息 ID 列表
     * @param userId         操作人（仅可删除自己发送的消息）
     */
    default void deleteMessages(String conversationId, List<String> msgIds, String userId) {
        throw new UnsupportedOperationException("deleteMessages not implemented");
    }

    /**
     * 物理删除指定 seq 范围内的消息。
     *
     * <p>消息将从存储中彻底移除，不可恢复。
     * 谨慎使用，通常仅用于合规删除或测试环境清理。</p>
     *
     * @param conversationId 会话 ID
     * @param startSeq       起始 seq（含），0=最早
     * @param endSeq         结束 seq（含），0=最新
     */
    default void deleteMessagesPhysical(String conversationId, long startSeq, long endSeq) {
        throw new UnsupportedOperationException("deleteMessagesPhysical not implemented");
    }

    /**
     * 清空会话的所有消息。
     *
     * <p>仅清除消息本身，不删除会话。
     * 清空后该会话的未读数归零。</p>
     *
     * @param conversationId 会话 ID
     */
    default void clearConversationMessages(String conversationId) {
        throw new UnsupportedOperationException("clearConversationMessages not implemented");
    }

    // ========================================
    //  消息搜索
    // ========================================

    /**
     * 搜索消息。
     *
     * <p>支持按关键词、时间范围、会话范围、发送者等条件组合过滤，
     * 仅返回搜索发起者参与过的消息（权限隔离）。</p>
     *
     * @param param 搜索条件，所有字段可选
     * @return 搜索结果（含消息列表 + 匹配总数）
     */
    default SearchMessagesResult searchMessages(SearchMessagesParam param) {
        throw new UnsupportedOperationException("searchMessages not implemented");
    }

    // ========================================
    //  消息撤回
    // ========================================

    /**
     * 撤回消息。
     * <p>将消息状态标记为已撤回（status=1），记录撤回人信息。</p>
     *
     * @param conversationId 会话 ID
     * @param seq            消息 seq
     * @param revokerId      撤回人 ID
     * @param role           撤回人角色（0=普通用户, 100=群主, 200=管理员）
     * @param nickname       撤回人昵称
     * @return true=撤回成功, false=消息不存在或已撤回
     */
    default boolean revokeMessage(String conversationId, long seq, String revokerId, int role, String nickname) {
        throw new UnsupportedOperationException("revokeMessage not implemented");
    }

    // ========================================
    //  已读回执（seq 追踪）
    // ========================================

    /**
     * 获取用户在指定会话的最新消息 seq（最新一条消息的序列号）。
     *
     * @param conversationId 会话 ID
     * @return 最新消息 seq，无消息返回 0
     */
    default long getLastSeq(String conversationId) {
        throw new UnsupportedOperationException("getLastSeq not implemented");
    }
}

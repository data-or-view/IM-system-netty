package com.im.api;

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
    void save(IMCommand msg);

    /**
     * 批量保存。
     */
    default void saveAll(List<IMCommand> messages) {
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
    List<IMCommand> pullOffline(String userId, int limit);

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
    List<IMCommand> pullBySequence(String conversationId, long startSeq, long endSeq, int limit);

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
}

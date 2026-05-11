package com.im.api;

/**
 * 消息序号管理器（按 conversation 递增）。
 *
 * 每个 conversation 有独立的单调递增 seq，用于：
 *   ① 消息排序（客户端按 seq 排序显示）
 *   ② 消息拉取（PullMessageBySeqList，客户端按 seq 范围拉）
 *   ③ 去重（同一 seq 的消息是重复的）
 *
 * 参考 OpenIM 的 GetMaxSeq / PullMessageBySeqs：
 *   · seq 按 conversation 递增，非全局
 *   · seq 由服务端分配（客户端不参与）
 *
 * Conversation ID 格式：
 *   · 单聊：single_{userA}_{userB}（字母序拼接）
 *   · 群聊：group_{groupId}
 */
public interface ISequenceManager {

    /**
     * 获取指定 conversation 的下一个 seq（原子递增）。
     */
    long nextSequence(String conversationId);

    /**
     * 获取指定 conversation 的当前最大 seq。
     */
    long getMaximumSequence(String conversationId);

    /**
     * 批量获取多个 conversation 的最大 seq。
     * 对应 OpenIM 的 GetConversationsHasReadAndMaxSeq。
     */
    default long[] getMaximumSequences(String[] conversationIds) {
        long[] seqs = new long[conversationIds.length];
        for (int i = 0; i < conversationIds.length; i++) {
            seqs[i] = getMaximumSequence(conversationIds[i]);
        }
        return seqs;
    }
}

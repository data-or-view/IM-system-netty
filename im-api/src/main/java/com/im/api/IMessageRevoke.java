package com.im.api;

/**
 * 消息撤回接口。
 *
 * 对应 OpenIM 的 RevokeMsg / RevokeMessageTips：
 *   发起撤回 → 服务端校验权限 → 修改消息状态 → 走 notification 通知双方
 *
 * 撤回的语义：
 *   消息被撤回后，接收方会话中会显示「xxx 撤回了一条消息」，
 *   但原始消息内容仍保留在服务端（用于审计/合规），只对用户隐藏。
 *
 * 撤回时间窗口：默认 2 分钟，可配置。
 * 群主/管理员可撤回任意群成员的消息，无时间限制。
 */
public interface IMessageRevoke {

    /**
     * 撤回消息。
     *
     * @param userId   撤回人
     * @param messageId    消息 ID
     * @param seq      消息 seq
     * @param groupId  群 ID（群聊撤回时必填，单聊空）
     * @return RevokeInfo 撤回结果
     * @throws ImException 如果超出撤回时间窗口或无权限
     */
    RevokeInfo revokeMessage(String userId, String messageId, int seq, String groupId);

    /**
     * 检查用户是否有权撤回。
     *
     * @param userId   撤回人
     * @param msg      原始消息
     * @param groupId  群 ID（可选）
     * @return true=允许撤回
     */
    boolean canRevoke(String userId, IMCommand msg, String groupId);

    /**
     * 撤回结果。
     */
    class RevokeInfo {
        private final String messageId;
        private final int seq;
        private final String revokerId;
        private final long revokeTime;

        public RevokeInfo(String messageId, int seq, String revokerId, long revokeTime) {
            this.messageId = messageId;
            this.seq = seq;
            this.revokerId = revokerId;
            this.revokeTime = revokeTime;
        }

        public String getMessageId() { return messageId; }
        public int getSeq() { return seq; }
        public String getRevokerId() { return revokerId; }
        public long getRevokeTime() { return revokeTime; }
    }
}

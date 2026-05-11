package com.im.api;

/**
 * Webhook / Callback 接口。
 *
 * 对应 OpenIM 的 webhook 机制：
 *   在消息发送、用户注册、群组操作等关键节点，
 *   以 HTTP POST 方式通知外部业务服务。
 *
 * 典型场景：
 *   · 消息敏感词审核（发送前拦截）
 *   · 消息记录归档（发送后同步到三方）
 *   · 推送通知触发（发给离线推送服务）
 *   · 业务数据同步（用户注册后同步到业务系统）
 *
 * 当前实现：LocalWebhookManager（占位 no-op）
 * 生产环境：HttpWebhookManager（HTTP POST + 签名 + 超时 + 重试）
 *
 * Webhook 调用方式：
 *   同步（before）：返回 false 可阻断操作（如审核不通过）
 *   异步（after）：不阻塞主流程，仅通知
 */
public interface IWebhookManager {

    /** Webhook 事件类型。 */
    enum Event {
        // ── 消息（新增 4 个核心 webhook） ──
        /** 单聊消息发送前（同步，可阻断） */
        BEFORE_SEND_SINGLE_MSG,
        /** 单聊消息发送后（异步通知） */
        AFTER_SEND_SINGLE_MSG,
        /** 群聊消息发送前（同步，可阻断） */
        BEFORE_SEND_GROUP_MSG,
        /** 群聊消息发送后（异步通知） */
        AFTER_SEND_GROUP_MSG,

        // ── 旧事件（保留向后兼容） ──
        /** @deprecated 请使用 BEFORE_SEND_SINGLE_MSG / BEFORE_SEND_GROUP_MSG */
        @Deprecated BEFORE_SEND_MSG,
        /** @deprecated 请使用 AFTER_SEND_SINGLE_MSG / AFTER_SEND_GROUP_MSG */
        @Deprecated AFTER_SEND_MSG,
        /** 用户注册前（可阻断） */
        BEFORE_USER_REGISTER,
        /** 用户注册后（通知） */
        AFTER_USER_REGISTER,
        /** 用户登录后（通知） */
        AFTER_USER_LOGIN,
        /** 群创建后（通知） */
        AFTER_GROUP_CREATED,
        /** 群成员变更后（通知） */
        AFTER_GROUP_MEMBER_CHANGE,
        /** 好友添加后（通知） */
        AFTER_FRIEND_ADDED,
    }

    /**
     * 触发同步 webhook（阻塞，返回 false 表示阻断操作）。
     *
     * @param event    事件类型
     * @param payload  事件数据（JSON 字符串）
     * @return true=放行, false=阻断
     */
    boolean callBefore(Event event, String payload);

    /**
     * 触发异步 webhook（不阻塞主流程）。
     *
     * @param event    事件类型
     * @param payload  事件数据（JSON 字符串）
     */
    void callAfterAsync(Event event, String payload);
}

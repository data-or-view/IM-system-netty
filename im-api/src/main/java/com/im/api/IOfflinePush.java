package com.im.api;

/**
 * 离线推送接口。
 *
 * 对应 OpenIM 的 push service + offlinepush 模块：
 *   消息发送时，如果接收方不在线，通过第三方推送服务通知用户。
 *
 * 对接的推送渠道：
 *   ▸ FCM（Firebase Cloud Messaging，Android 海外）
 *   ▸ APNs（Apple Push Notification，iOS）
 *   ▸ 华为推送（HMS，Android 国内）
 *   ▸ 个推 / 极光（国内第三方聚合）
 *
 * 离线推送触发时机：
 *   DeliveryConsumer 中查路由表发现全部离线 → 调用本接口发送推送。
 *
 * 当前实现：LocalOfflinePush（占位 no-op）。
 */
public interface IOfflinePush {

    /**
     * 推送离线通知。
     *
     * @param userId       目标用户
     * @param title        通知标题（通常为发送者昵称）
     * @param body         通知正文（消息预览）
     * @param conversationId 会话 ID（点击通知打开对应会话）
     * @param signalInfo   音视频通话信令（可选，传递后唤起呼叫界面）
     */
    void push(String userId, String title, String body,
              String conversationId, String signalInfo);
}

package com.im.api;

/**
 * 离线推送接口。
 *
 * <p>TODO: 用户不在线时通过第三方推送服务（FCM/APNs/Web Push 等）
 * 给用户手机发送通知栏消息，类似微信/QQ 离线时弹出的消息通知。
 * 当前未对接任何推送服务，所有消息仅打日志。</p>
 *
 * <p>对接时机：有移动端 App 或 Web Push 需求时实现此接口。</p>
 */
public interface IOfflinePusher {

    /**
     * 推送离线通知。
     *
     * @param message 推送消息内容
     */
    void push(OfflinePushMessage message);

    /**
     * 离线推送消息。
     *
     * @param userId        目标用户
     * @param title         通知标题（如发送者昵称）
     * @param content       通知内容（如消息预览）
     * @param conversationId 会话 ID
     * @param seq           消息 seq
     */
    record OfflinePushMessage(String userId, String title, String content,
                              String conversationId, long seq) {}
}

package com.im.api;

/**
 * 音视频通话管理接口。
 *
 * 职责：管理 RTC 房间和 room token 的签发。
 * 不处理媒体面——媒体流由第三方 SFU 处理。
 *
 * 信令消息（invite/accept/reject/hangup/ice）走 IM 消息管道，
 * 作为 ContentType.SIGNAL 的自定义消息通过 ChatHandler 路由，
 * 本接口不处理信令转发。
 *
 * 工作流：
 *   ① 主叫发起通话 → ChatHandler(ContentType.SIGNAL + INVITE)
 *      → 内部调用 ICallManager.createRoom() 创建 SFU 房间 + 签发 token
 *      → INVITE 消息体中携带 roomId + callerToken + sfuEndpoint
 *   ② 服务端转发 INVITE 给被叫（走标准 MQ 投递）
 *   ③ 被叫同意 → ACCEPT 消息通过 IM 管道返回
 *   ④ 主叫收到 ACCEPT → 双方用各自的 token 连接 SFU
 *      （主叫用 callerToken，被叫用 calleeToken）
 *   ⑤ ICE candidate 交换 → ICE 信令消息通过 IM 管道透传
 *   ⑥ 挂断 → HANGUP 信令消息
 *
 * 对接的 SFU（客户端自行实现的适配器）：
 *   ▸ LiveKit（推荐，Github 28k+ ⭐）
 *   ▸ MediaSoup（更底层，灵活度高）
 *   ▸ Agora（云服务）
 *   ▸ 腾讯 TRTC（云服务）
 *
 * 参考 OpenIM：OpenIM 无内置 RTC，采用相同模式
 *   （custom content type + 第三方 SFU）。
 */
public interface ICallManager {

    /**
     * 创建 SFU 房间并签发 room token。
     *
     * @param callerId  主叫用户 ID
     * @param calleeId  被叫用户 ID
     * @param roomId    可选房间 ID（null 时自动生成）
     * @return 房间信息（含 token + SFU 地址）
     */
    RoomInformation createRoom(String callerId, String calleeId, String roomId);

    /**
     * 为用户签发加入指定房间的 token。
     * 被叫接受通话后，用此 token 加入房间。
     *
     * @param userId 用户 ID
     * @param roomId 房间 ID
     * @return room token
     */
    String issueToken(String userId, String roomId);

    /**
     * 获取使用的 SFU 提供商名称。
     * 用于文档输出和日志记录。
     */
    String getProviderName();

    /**
     * 获取 SFU 端点地址（WebSocket 连接地址）。
     */
    String getSfuEndpoint();
}

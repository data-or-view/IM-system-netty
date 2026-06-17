package com.im.api.content;

import com.im.api.SignalingAction;

/**
 * 音视频通话信令消息内容。
 *
 * 作为自定义 ContentType 通过 IM 消息管道发送。
 * 服务端只负责转发，不解析 SDP/ICE 内容。
 *
 * 配合第三方 SFU（LiveKit / MediaSoup / Agora 等）使用：
 *   IM 管道    = 信令通道（invite/accept/ice 等）
 *   SFU        = 媒体通道（实际的编码音视频流）
 *
 * 消息体 body 为 JSON 字符串，格式：
 *   {
 *       "_act": 1,             // SignalingAction.code
 *       "_room": "room_abc",   // 房间 ID
 *       "_token": "xxx",       // SFU room token（由 ICallManager 签发）
 *       "_sdp": "",            // WebRTC SDP offer/answer 可选
 *       "_ice": "",            // ICE candidate 可选
 *   }
 */
public class SignalingContent implements IMessageContent {

    /** 信令动作 */
    private SignalingAction action;
    /** 通话类型：voice / video */
    private String callType;
    /** 房间 ID */
    private String roomId;
    /** SFU room token */
    private String token;
    /** SFU endpoint */
    private String sfuEndpoint;
    /** SDP offer/answer（JSON string） */
    private String sdp;
    /** ICE candidate（JSON string） */
    private String ice;
    /** 通话时长（秒，仅 HANGUP 时携带） */
    private int duration;

    /** Jackson 反序列化用 */
    public SignalingContent() {
    }

    public SignalingContent(SignalingAction action, String roomId, String token,
                            String sdp, String ice, int duration) {
        this.action = action;
        this.roomId = roomId;
        this.token = token;
        this.sdp = sdp;
        this.ice = ice;
        this.duration = duration;
    }

    public SignalingContent(SignalingAction action, String callType, String roomId, String token,
                            String sdp, String ice, int duration) {
        this(action, roomId, token, sdp, ice, duration);
        this.callType = callType;
    }

    public SignalingContent(SignalingAction action, String callType, String roomId, String token,
                            String sfuEndpoint, String sdp, String ice, int duration) {
        this(action, callType, roomId, token, sdp, ice, duration);
        this.sfuEndpoint = sfuEndpoint;
    }

    public SignalingContent(SignalingAction action, String roomId, String token) {
        this(action, roomId, token, null, null, 0);
    }

    @Override
    public ContentType getContentType() {
        return ContentType.SIGNAL;
    }

    @Override
    public void validate() {
        if (action == null) {
            throw new IllegalArgumentException("SignalingContent: action is required");
        }
    }

    public SignalingAction getAction() { return action; }
    public String getCallType() { return callType; }
    public String getRoomId() { return roomId; }
    public String getToken() { return token; }
    public String getSfuEndpoint() { return sfuEndpoint; }
    public String getSdp() { return sdp; }
    public String getIce() { return ice; }
    public int getDuration() { return duration; }

    public void setAction(SignalingAction action) { this.action = action; }
    public void setCallType(String callType) { this.callType = callType; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setToken(String token) { this.token = token; }
    public void setSfuEndpoint(String sfuEndpoint) { this.sfuEndpoint = sfuEndpoint; }
    public void setSdp(String sdp) { this.sdp = sdp; }
    public void setIce(String ice) { this.ice = ice; }
    public void setDuration(int duration) { this.duration = duration; }
}

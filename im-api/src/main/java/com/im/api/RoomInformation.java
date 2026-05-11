package com.im.api;

/**
 * 通话房间信息。
 *
 * 由 ICallManager.createRoom 返回，包含：
 *   房间 ID、SFU 端点地址、主叫/被叫各自的 room token
 *
 * 客户端拿到 RoomInformation 后：
 *   主叫 → 用 callerToken 加入房间
 *   被叫 → 用 calleeToken 加入房间
 *   双方 → 通过 SFU 端点直连媒体流
 */
public class RoomInformation {

    private final String roomId;
    private final String sfuEndpoint;
    private final String callerToken;
    private final String calleeToken;

    public RoomInformation(String roomId, String sfuEndpoint,
                    String callerToken, String calleeToken) {
        this.roomId = roomId;
        this.sfuEndpoint = sfuEndpoint;
        this.callerToken = callerToken;
        this.calleeToken = calleeToken;
    }

    public String getRoomId() { return roomId; }
    public String getSfuEndpoint() { return sfuEndpoint; }
    public String getCallerToken() { return callerToken; }
    public String getCalleeToken() { return calleeToken; }
}

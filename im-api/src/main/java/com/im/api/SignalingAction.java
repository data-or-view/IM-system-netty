package com.im.api;

import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;

/**
 * RTC 信令动作枚举。
 *
 * 通话过程中的状态机由双方客户端协商，服务端只负责转发信令消息。
 *
 * 典型流程：
 *   invite(主叫) ──→ calling(主叫) ──→ accept(被叫) ──→ on_ice(双方) ──→ connected
 *   invite(主叫) ──→ reject(被叫) ──→ canceled(主叫)
 *   connected ──→ hangup(任一方)
 */
public enum SignalingAction {

    /** 发起通话（主叫→被叫，body 携带 SDP offer + room token） */
    INVITE(1),
    /** 对方振铃中（服务端透传 INVITE 给被叫） */
    CALLING(2),
    /** 接听（被叫→主叫，body 携带 SDP answer） */
    ACCEPT(3),
    /** 拒绝（被叫→主叫） */
    REJECT(4),
    /** 取消（主叫在振铃中主动取消） */
    CANCEL(5),
    /** 挂断（任一方通话中挂断） */
    HANGUP(6),
    /** ICE candidate 交换（双方互相 ICE 候选地址） */
    ICE(7),
    /** 通话超时未接（服务端主动通知双方） */
    TIMEOUT(8);

    private final int code;

    SignalingAction(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SignalingAction fromCode(int code) {
        return switch (code) {
            case 1 -> INVITE;
            case 2 -> CALLING;
            case 3 -> ACCEPT;
            case 4 -> REJECT;
            case 5 -> CANCEL;
            case 6 -> HANGUP;
            case 7 -> ICE;
            case 8 -> TIMEOUT;
            default -> throw new ImException(ImErrorCode.BAD_REQUEST,
                    "unknown signaling action: " + code);
        };
    }
}

package com.im.api;

public enum GroupJoinVerification {
    DIRECT(0),
    NEED_APPROVAL(1),
    INVITE_ONLY(2),
    FORBIDDEN(3);

    private final int code;

    GroupJoinVerification(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static GroupJoinVerification fromCode(int code) {
        for (GroupJoinVerification value : values()) {
            if (value.code == code) return value;
        }
        return DIRECT;
    }
}

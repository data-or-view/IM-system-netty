package com.im.api;

public enum ConversationType {
    SINGLE(1),
    GROUP(2);

    private final int code;

    ConversationType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ConversationType fromCode(int code) {
        for (ConversationType value : values()) {
            if (value.code == code) return value;
        }
        throw new IllegalArgumentException("Unknown conversation type code: " + code);
    }
}

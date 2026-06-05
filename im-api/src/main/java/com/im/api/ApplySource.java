package com.im.api;

public enum ApplySource {
    UNKNOWN(0),
    SEARCH(1),
    QR_CODE(2),
    GROUP(3),
    INVITE(4);

    private final int code;

    ApplySource(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ApplySource fromCode(int code) {
        for (ApplySource value : values()) {
            if (value.code == code) return value;
        }
        return UNKNOWN;
    }
}

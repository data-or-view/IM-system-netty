package com.im.api;

public enum ApplyHandleResult {
    PENDING(0),
    AGREED(1),
    REJECTED(2);

    private final int code;

    ApplyHandleResult(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ApplyHandleResult fromCode(int code) {
        for (ApplyHandleResult value : values()) {
            if (value.code == code) return value;
        }
        throw new IllegalArgumentException("Unknown apply handle result code: " + code);
    }
}

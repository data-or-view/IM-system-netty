package com.im.api;

public enum GroupStatus {
    DISBANDED(0),
    NORMAL(1);

    private final int code;

    GroupStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static GroupStatus fromCode(int code) {
        for (GroupStatus value : values()) {
            if (value.code == code) return value;
        }
        return NORMAL;
    }
}

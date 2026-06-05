package com.im.api;

public enum GroupAtType {
    NONE(0),
    AT_ME(1),
    AT_ALL(2);

    private final int code;

    GroupAtType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static GroupAtType fromCode(int code) {
        for (GroupAtType value : values()) {
            if (value.code == code) return value;
        }
        return NONE;
    }
}

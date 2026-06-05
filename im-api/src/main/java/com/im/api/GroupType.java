package com.im.api;

public enum GroupType {
    PRIVATE(0),
    PUBLIC(1);

    private final int code;

    GroupType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static GroupType fromCode(int code) {
        for (GroupType value : values()) {
            if (value.code == code) return value;
        }
        return PRIVATE;
    }
}

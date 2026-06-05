package com.im.api;

public enum UserAdminLevel {
    NORMAL(0),
    ADMIN(1),
    SUPER_ADMIN(2);

    private final int code;

    UserAdminLevel(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static UserAdminLevel fromCode(int code) {
        for (UserAdminLevel value : values()) {
            if (value.code == code) return value;
        }
        return NORMAL;
    }
}

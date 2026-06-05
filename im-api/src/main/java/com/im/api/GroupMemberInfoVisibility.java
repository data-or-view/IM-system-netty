package com.im.api;

public enum GroupMemberInfoVisibility {
    ALL_VISIBLE(0),
    ADMIN_ONLY(1);

    private final int code;

    GroupMemberInfoVisibility(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static GroupMemberInfoVisibility fromCode(int code) {
        for (GroupMemberInfoVisibility value : values()) {
            if (value.code == code) return value;
        }
        return ALL_VISIBLE;
    }
}

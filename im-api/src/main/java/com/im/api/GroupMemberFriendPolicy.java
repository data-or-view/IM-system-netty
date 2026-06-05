package com.im.api;

public enum GroupMemberFriendPolicy {
    ALLOW(0),
    DENY(1);

    private final int code;

    GroupMemberFriendPolicy(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static GroupMemberFriendPolicy fromCode(int code) {
        for (GroupMemberFriendPolicy value : values()) {
            if (value.code == code) return value;
        }
        return ALLOW;
    }
}

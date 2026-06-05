package com.im.api;

public enum GroupMemberRole {
    REMOVED(-1),
    MEMBER(1),
    ADMIN(100),
    OWNER(200);

    private final int code;

    GroupMemberRole(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public boolean isOwner() {
        return this == OWNER;
    }

    public boolean isAdminOrOwner() {
        return code >= ADMIN.code;
    }

    public static GroupMemberRole fromCode(int code) {
        for (GroupMemberRole value : values()) {
            if (value.code == code) return value;
        }
        return MEMBER;
    }
}

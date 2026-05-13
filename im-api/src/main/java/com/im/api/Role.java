package com.im.api;

/**
 * 权限角色枚举。
 *
 * <p>按级别分层：数字越大权限越高，高级别自动拥有低级别权限。</p>
 *
 * <p>Token 中存储 {@code appManagerLevel}（0-2 数字），对应关系：</p>
 * <pre>
 *   level=0 → USER     （普通用户）
 *   level=1 → ADMIN    （管理员）
 *   level=2 → SUPER_ADMIN（超级管理员）
 * </pre>
 */
public enum Role {

    /** 公开（无需登录） */
    PUBLIC(0, "public", 0),

    /** 已登录用户 */
    USER(1, "user", 0),

    /** 系统管理员（appManagerLevel >= 1） */
    ADMIN(2, "admin", 1),

    /** 超级管理员（appManagerLevel >= 2） */
    SUPER_ADMIN(3, "super_admin", 2);

    private final int code;
    private final String name;
    private final int requiredLevel;

    Role(int code, String name, int requiredLevel) {
        this.code = code;
        this.name = name;
        this.requiredLevel = requiredLevel;
    }

    public int getCode() { return code; }
    public String getName() { return name; }
    public int getRequiredLevel() { return requiredLevel; }

    /**
     * 判断当前角色是否能访问需要 {@code required} 权限的操作。
     * 高级别自动拥有低级别权限（code 数值比较）。
     */
    public boolean canAccess(Role required) {
        return this.code >= required.code;
    }

    /**
     * 从 appManagerLevel 数字映射到 Role。
     */
    public static Role fromAppManagerLevel(int appManagerLevel) {
        if (appManagerLevel >= 2) return SUPER_ADMIN;
        if (appManagerLevel >= 1) return ADMIN;
        return USER;
    }

    public static Role fromCode(int code) {
        for (Role r : values()) {
            if (r.code == code) return r;
        }
        return PUBLIC;
    }
}

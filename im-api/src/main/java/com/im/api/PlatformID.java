package com.im.api;

/**
 * 平台 ID 常量，对应 OpenIM 的 PlatformID。
 *
 * 用于标识用户登录的设备类型，支持多端在线策略判断。
 */
public final class PlatformID {

    private PlatformID() {}

    public static final int IOS = 1;
    public static final int ANDROID = 2;
    public static final int WINDOWS = 3;
    public static final int MACOS = 4;
    public static final int WEB = 5;
    public static final int LINUX = 7;

    /** 默认平台（未指定时使用） */
    public static final int DEFAULT = WEB;

    public static String name(int platformId) {
        return switch (platformId) {
            case IOS -> "iOS";
            case ANDROID -> "Android";
            case WINDOWS -> "Windows";
            case MACOS -> "macOS";
            case WEB -> "Web";
            case LINUX -> "Linux";
            default -> "Unknown(" + platformId + ")";
        };
    }
}

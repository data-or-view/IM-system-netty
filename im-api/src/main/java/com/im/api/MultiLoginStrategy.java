package com.im.api;

/**
 * 多端登录策略。
 *
 * 参考 OpenIM 的 multiLoginPolicy（1=单端登录，2=多端登录，3=互踢）：
 *   · ALLOW_MULTIPLE：允许同一用户同时在线（手机+PC+Web），默认行为
 *   · KICK_OLD：新登录踢掉旧端（微信模式）
 *   · REJECT_NEW：已有在线时拒绝新登录（金融/安全场景）
 */
public enum MultiLoginStrategy {

    /**
     * 允许同一用户多端在线，不踢旧端。
     * 适用于社交类 IM。
     */
    ALLOW_MULTIPLE,

    /**
     * 新登录踢掉旧端。
     * 适用于需要单点登录的场景。
     */
    KICK_OLD,

    /**
     * 已有在线时拒绝新登录。
     * 适用于强安全场景。
     */
    REJECT_NEW
}

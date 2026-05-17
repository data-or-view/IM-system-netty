package com.im.api;

/**
 * 多端登录策略。
 *
 * <p>参考 OpenIM 的 multiLoginPolicy：</p>
 * <ul>
 *   <li>{@link #ALLOW_MULTIPLE} — 允许同一用户多端同时在线（手机+PC+Web），不踢旧端</li>
 *   <li>{@link #KICK_OLD} — 新登录踢掉所有旧端（微信模式）</li>
 *   <li>{@link #SAME_TERM_KICK} — 同平台互踢。手机再登录只踢旧手机，PC 不受影响</li>
 *   <li>{@link #REJECT_NEW} — 已有在线时拒绝新登录（金融/安全场景）</li>
 * </ul>
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
     * 同平台互踢。
     * 同一平台（platformId）的新登录会踢掉该平台的旧连接，
     * 其他平台不受影响。
     * 对应 OpenIM 的 AllLoginButSameTermKick。
     */
    SAME_TERM_KICK,

    /**
     * 已有在线时拒绝新登录。
     * 适用于强安全场景。
     */
    REJECT_NEW
}

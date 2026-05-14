package com.im.api;

import java.util.List;
import java.util.Map;

/**
 * 用户管理接口。
 *
 * 对应 OpenIM 的 user rpc service：
 *   user_register / get_users_info / get_users_online_status
 *
 * 用户数据必须崩溃持久化（生产环境用 DB/Redis）。
 * 在线状态数据实时性高，建议 Redis。
 */
public interface IUserManager {

    /**
     * 注册用户。
     *
     * @param userId   用户 ID
     * @param nickname 昵称
     * @param faceUrl  头像 URL（可选）
     * @param ex       扩展字段（可选）
     * @throws ImException 如果用户已存在
     */
    void register(String userId, String nickname, String faceUrl, String ex);

    /**
     * 获取用户公开信息。
     */
    UserInformation getUserInformation(String userId);

    /**
     * 批量获取用户公开信息。
     */
    List<UserInformation> getUsersInfo(List<String> userIds);

    /**
     * 查询用户在线状态。
     *
     * @return userId → 在线平台列表（空列表 = 不在线）
     */
    Map<String, List<Integer>> getOnlineStatus(List<String> userIds);

    /**
     * 更新用户资料。
     *
     * @param userId           用户 ID
     * @param nickname         昵称（null 表示不更新）
     * @param faceUrl          头像 URL（null 表示不更新）
     * @param ex               扩展字段（null 表示不更新）
     * @param globalRecvMsgOpt 全局消息接收选项（-1 表示不更新）
     */
    void updateUserInformation(String userId, String nickname, String faceUrl,
                               String ex, int globalRecvMsgOpt);

    /**
     * 搜索用户（按昵称或 user_id 模糊匹配）。
     *
     * @param keyword 搜索关键词
     * @param limit   最大返回条数（默认 20）
     */
    List<UserInformation> searchUsers(String keyword, int limit);

    // ========================================
    //  在线状态订阅
    // ========================================

    /**
     * 订阅指定用户的在线状态变更。
     *
     * <p>订阅后，当被订阅用户的在线状态发生变化时，
     * 系统会向订阅者推送状态变更通知。
     * 典型场景：聊天列表中好友的头像"在线"状态实时更新。</p>
     *
     * @param subscriberUserId 订阅者
     * @param targetUserIds    要订阅状态的用户列表
     */
    default void subscribeOnlineStatus(String subscriberUserId, List<String> targetUserIds) {
        throw new UnsupportedOperationException("subscribeOnlineStatus not implemented");
    }

    /**
     * 取消订阅用户的在线状态。
     *
     * @param subscriberUserId 订阅者
     * @param targetUserIds    要取消订阅的用户列表
     */
    default void unsubscribeOnlineStatus(String subscriberUserId, List<String> targetUserIds) {
        throw new UnsupportedOperationException("unsubscribeOnlineStatus not implemented");
    }

    /**
     * 获取订阅用户的在线状态。
     *
     * <p>返回当前订阅的所有用户（或指定列表）的在线状态。
     * 一次性查询，不涉及后续推送。</p>
     *
     * @param userId       用户 ID
     * @param targetUserId 指定要查的用户（null=查所有已订阅的）
     * @return userId → 在线平台 ID 列表（空列表=不在线）
     */
    default Map<String, List<Integer>> getSubscribedStatus(String userId, String targetUserId) {
        throw new UnsupportedOperationException("getSubscribedStatus not implemented");
    }

    // ========================================
    //  用户管理（管理员接口）
    // ========================================

    /**
     * 分页查询所有用户（管理后台）。
     *
     * @param offset 偏移量
     * @param limit  每页条数
     * @return 用户列表
     */
    default List<UserInformation> getAllUsers(int offset, int limit) {
        throw new UnsupportedOperationException("getAllUsers not implemented");
    }

    /**
     * 批量检查用户是否存在。
     *
     * @param userIds 用户 ID 列表
     * @return 存在的用户 ID → true, 不存在 → false
     */
    default Map<String, Boolean> checkAccounts(List<String> userIds) {
        throw new UnsupportedOperationException("checkAccounts not implemented");
    }
}

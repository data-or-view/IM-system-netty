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
     */
    void updateUserInformation(String userId, String nickname, String faceUrl, String ex);
}

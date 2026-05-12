package com.im.api;

import java.util.List;

/**
 * 好友关系链管理接口。
 *
 * 对应 OpenIM 的 relation rpc service：
 *   add_friend / respond_friend_apply / delete_friend
 *   get_friend_list / add_black / get_black_list
 *
 * 好友关系数据必须崩溃持久化。
 *
 * 对接 OpenIM 客户端 SDK 注意：
 *   OpenIM 好友关系 API 与 SDK 深度集成（好友添加 / 拉黑 / 申请审批），
 *   客户端 SDK 中内置了基于这些接口的好友 UI 交互流程。
 *   我们的接口设计与 OpenIM 保持语义一致，方便后续对接 SDK。
 */
public interface IFriendManager {

    // ========== 好友申请 ==========

    /**
     * 申请添加好友。
     *
     * @param fromUserId 申请人
     * @param toUserId   被申请人
     * @param reqMsg     申请附言
     */
    void applyAddFriend(String fromUserId, String toUserId, String reqMsg);

    /**
     * 处理好友申请。
     *
     * @param userId      处理人
     * @param fromUserId  申请人
     * @param handleMsg   处理附言
     * @param agreed      true=同意, false=拒绝
     */
    void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed);

    /**
     * 获取好友申请列表。
     *
     * @param userId 用户 ID
     * @param onlyPending true=只查待处理的, false=全部
     */
    List<FriendApply> getFriendApplyList(String userId, boolean onlyPending);

    // ========== 好友管理 ==========

    /**
     * 删除好友。
     */
    void deleteFriend(String ownerUserId, String friendUserId);

    /**
     * 获取用户的好友列表。
     */
    List<FriendInformation> getFriendList(String userId);

    /**
     * 判断是否为好友关系。
     */
    boolean isFriend(String userIdA, String userIdB);

    /**
     * 设置好友备注。
     */
    void setFriendRemark(String ownerUserId, String friendUserId, String remark);

    /**
     * 设置好友置顶。
     */
    void setFriendPinned(String ownerUserId, String friendUserId, boolean pinned);

    // ========== 黑名单 ==========

    /**
     * 拉黑用户。
     */
    void addBlack(String ownerUserId, String blockedUserId);

    /**
     * 移除黑名单。
     */
    void removeBlack(String ownerUserId, String blockedUserId);

    /**
     * 获取黑名单列表。
     */
    List<String> getBlackList(String userId);

    /**
     * 检查是否被拉黑（发送时校验）。
     *
     * @return true 如果 toUserId 拉黑了 fromUserId
     */
    boolean isBlocked(String fromUserId, String toUserId);
}

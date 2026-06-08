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
     *
     * @return true=本次确实删除了好友关系, false=双方原本不是好友
     */
    boolean deleteFriend(String ownerUserId, String friendUserId);

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

    // ========================================
    //  好友增量同步
    // ========================================

    /**
     * 增量同步好友列表。
     *
     * <p>客户端传入上次同步的 version，服务端返回新增/删除的好友。
     * 删除的好友通过 {@link FriendInformation#isDeleted()} 标记。</p>
     *
     * @param userId  用户 ID
     * @param version 客户端已知的最新 version，0=全量同步
     * @return 增量同步结果
     */
    default IncrementalSyncResult<FriendInformation> getIncrementalFriends(String userId, long version) {
        throw new UnsupportedOperationException("getIncrementalFriends not implemented");
    }

    /**
     * 批量导入好友（数据迁移场景）。
     *
     * <p>跳过已存在的好友关系（幂等），返回实际新增的数量。</p>
     *
     * @param userId         用户 ID
     * @param friendUserIds  待导入的好友 ID 列表
     * @return 实际添加的好友数
     */
    default int importFriends(String userId, List<String> friendUserIds) {
        throw new UnsupportedOperationException("importFriends not implemented");
    }

    // ========================================
    //  黑名单增量同步
    // ========================================

    /**
     * 增量同步黑名单。
     *
     * @param userId  用户 ID
     * @param version 客户端已知的最新 version
     * @return 增量同步结果
     */
    default IncrementalSyncResult<String> getIncrementalBlacks(String userId, long version) {
        throw new UnsupportedOperationException("getIncrementalBlacks not implemented");
    }

    // ========================================
    //  好友申请查询
    // ========================================

    /**
     * 获取用户已发出的好友申请列表。
     *
     * @param userId 用户 ID
     * @return 申请列表（含已处理/待处理）
     */
    default List<FriendApply> getSentFriendApplyList(String userId) {
        throw new UnsupportedOperationException("getSentFriendApplyList not implemented");
    }

    /**
     * 获取指定好友申请的详情。
     *
     * @param fromUserId 申请人
     * @param toUserId   被申请人
     * @return 申请详情，不存在返回 null
     */
    default FriendApply getFriendApplyDetail(String fromUserId, String toUserId) {
        throw new UnsupportedOperationException("getFriendApplyDetail not implemented");
    }

    /**
     * 获取用户待处理的好友申请数（红点提示）。
     */
    default int getUnhandledApplyCount(String userId) {
        throw new UnsupportedOperationException("getUnhandledApplyCount not implemented");
    }
}

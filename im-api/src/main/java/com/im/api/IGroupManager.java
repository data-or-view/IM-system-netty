package com.im.api;

import java.util.List;
import java.util.Set;

/**
 * 群组/群成员管理接口。
 *
 * 对应 OpenIM 的 group RPC（groupRpcClient）：
 *   · GetGroupMemberList → 获取群成员
 *   · GetGroupMemberIDs → 获取群成员 ID 列表
 *   · CreateGroup → 创建群
 *   · JoinGroup / QuitGroup
 *   · ApplicationGroupResponse → 审批加群申请
 */
public interface IGroupManager {

    // ========== 群生命周期 ==========

    /**
     * 创建群组。
     *
     * @param groupId         群 ID
     * @param ownerId         群主 ID
     * @param groupName       群名称
     * @param faceUrl         群头像（可选）
     * @param members         初始成员列表
     * @param groupType       群类型: 0=私有群, 1=公开群
     * @param needVerification 加群验证: 0=无条件, 1=需验证, 2=需邀请, 3=不允许
     */
    void createGroup(String groupId, String ownerId, String groupName, String faceUrl,
                     List<String> members, int groupType, int needVerification);

    /**
     * 解散群组。
     * 只有群主可解散。
     */
    void disbandGroup(String groupId, String operatorId);

    /**
     * 修改群信息。
     *
     * @param groupId              群 ID
     * @param groupName            群名称（null=不更新）
     * @param notification         群公告（null=不更新）
     * @param introduction         群简介（null=不更新）
     * @param faceUrl              群头像（null=不更新）
     * @param needVerification     加群验证（-1=不更新）
     * @param lookMemberInfo       成员信息可见（-1=不更新）
     * @param applyMemberFriend    允许互加好友（-1=不更新）
     * @param notificationUserId   公告更新人（null=不更新）
     */
    void setGroupInformation(String groupId, String groupName, String notification,
                             String introduction, String faceUrl, int needVerification,
                             int lookMemberInfo, int applyMemberFriend,
                             String notificationUserId);

    // ========== 成员管理 ==========

    /**
     * 添加成员到群组。
     */
    void addMember(String groupId, String userId);

    /**
     * 批量添加成员。
     */
    void addMembers(String groupId, List<String> userIds);

    /**
     * 移除成员（踢人）。
     * 群主/管理员可执行。
     */
    void kickMember(String groupId, String operatorId, String targetUserId);

    /**
     * 主动退群。
     */
    void quitGroup(String groupId, String userId);

    /**
     * 转让群主。
     */
    void transferOwner(String groupId, String oldOwnerId, String newOwnerId);

    /**
     * 设置成员角色。
     *
     * @param roleLevel 1=普通成员, 100=管理员, 200=群主
     */
    void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel);

    /**
     * 禁言/解除禁言成员。
     *
     * @param groupId        群 ID
     * @param targetUserId   目标用户
     * @param muteEndTime    禁言截止时间(毫秒)，0=解除禁言
     */
    void muteMember(String groupId, String targetUserId, long muteEndTime);

    // ========== 加群申请流程 ==========

    /**
     * 申请加群。
     */
    GroupJoinResult joinGroup(String groupId, String userId, String reqMsg);

    /**
     * 审批加群申请。
     *
     * @param agreed true=同意, false=拒绝
     */
    GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId,
                                              String handleMsg, boolean agreed);

    /**
     * 获取加群申请列表。
     *
     * @param groupId     群 ID（null=获取用户自己的所有申请记录）
     * @param onlyPending true=只查待处理的
     */
    List<GroupApply> getJoinRequests(String groupId, boolean onlyPending);

    /**
     * 获取当前操作者有权管理的群加群申请。
     *
     * <p>只应返回操作者是群主或管理员的群申请，避免 groupId=null 时泄露全库申请。</p>
     *
     * @param operatorId   当前操作者用户 ID
     * @param onlyPending  true=只查待处理的
     */
    default List<GroupApply> getManageableJoinRequests(String operatorId, boolean onlyPending) {
        return List.of();
    }

    default List<String> getManagerIds(String groupId) {
        return List.of();
    }

    /**
     * 全员禁言。
     *
     * <p>全员禁言后，只有群主和管理员可以发言。
     * 对应 OpenIM 的 mute_group / cancel_mute_group。</p>
     *
     * @param groupId    群 ID
     * @param operatorId 操作人
     * @param mute       true=禁言, false=解除禁言
     */
    default void muteGroupAll(String groupId, String operatorId, boolean mute) {
        throw new UnsupportedOperationException("muteGroupAll not implemented");
    }

    /**
     * 判断群成员是否被禁言。
     *
     * @param groupId 群 ID
     * @param userId  成员 ID
     * @return true 如果该成员被禁言且禁言未过期
     */
    default boolean isMemberMuted(String groupId, String userId) {
        throw new UnsupportedOperationException("isMemberMuted not implemented");
    }

    /**
     * 设置群成员自定义信息（如群昵称）。
     *
     * @param groupId    群 ID
     * @param userId     成员 ID
     * @param ex         扩展字段（JSON 字符串，业务自定义）
     */
    default void setMemberInfo(String groupId, String userId, String ex) {
        throw new UnsupportedOperationException("setMemberInfo not implemented");
    }

    /**
     * 邀请用户入群（无需申请，直接加入）。
     *
     * <p>与 {@link #joinGroup} 的区别：invite 不需要对方同意，
     * 由群内成员直接拉人入群。</p>
     *
     * @param groupId    群 ID
     * @param operatorId 邀请人（群成员）
     * @param userIds    被邀请的用户 ID 列表
     */
    default void inviteMembers(String groupId, String operatorId, List<String> userIds) {
        throw new UnsupportedOperationException("inviteMembers not implemented");
    }

    /**
     * 获取群抽象信息（成员数、在线数等汇总）。
     *
     * @param groupId 群 ID
     * @return 群抽象信息
     */
    default GroupAbstractInfo getGroupAbstractInfo(String groupId) {
        throw new UnsupportedOperationException("getGroupAbstractInfo not implemented");
    }

    // ========================================
    //  群增量同步
    // ========================================

    /**
     * 增量同步用户加入的群列表。
     *
     * @param userId  用户 ID
     * @param version 客户端已知的最新 version
     * @return 新增/退出的群 ID 列表
     */
    default IncrementalSyncResult<String> getIncrementalGroups(String userId, long version) {
        throw new UnsupportedOperationException("getIncrementalGroups not implemented");
    }

    /**
     * 增量同步群成员。
     *
     * @param groupId 群 ID
     * @param version 客户端已知的最新 version
     * @return 新增/移除的成员列表
     */
    default IncrementalSyncResult<GroupMemberInformation> getIncrementalMembers(String groupId, long version) {
        throw new UnsupportedOperationException("getIncrementalMembers not implemented");
    }

    // ========== 查询 ==========

    /**
     * 获取群成员信息列表。
     */
    List<GroupMemberInformation> getMemberList(String groupId);

    /**
     * 获取群成员 ID 列表。
     *
     * @return 成员 userId 集合（含群主），群不存在返回空集
     */
    Set<String> getMemberIds(String groupId);

    /**
     * 判断用户是否群成员。
     */
    boolean isMember(String groupId, String userId);

    /**
     * 获取用户在群内的角色。
     *
     * @return "owner" / "admin" / "member" / null（非成员）
     */
    String getRole(String groupId, String userId);

    /**
     * 用户加入的群列表。
     */
    Set<String> getJoinedGroups(String userId);

    /**
     * 用户加入的群信息列表。
     */
    default List<GroupInformation> getJoinedGroupInformationList(String userId) {
        return getJoinedGroups(userId).stream()
                .map(this::getGroupInformation)
                .filter(info -> info != null)
                .toList();
    }

    /**
     * 获取群信息。
     */
    GroupInformation getGroupInformation(String groupId);

    /**
     * 搜索群组（按群名关键词）。
     * @return 匹配的群组列表，按创建时间倒序
     */
    List<GroupInformation> searchGroups(String keyword, int limit);
}

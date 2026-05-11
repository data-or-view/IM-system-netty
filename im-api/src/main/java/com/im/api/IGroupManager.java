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
 *
 * 当前实现：LocalGroupManager（内存 HashMap）
 * 生产环境：DBGroupManager（MySQL/PostgreSQL）或 RedisGroupManager
 */
public interface IGroupManager {

    // ========== 群生命周期 ==========

    /**
     * 创建群组。
     *
     * @param groupId  群 ID
     * @param ownerId  群主 ID
     * @param groupName 群名称
     * @param members  初始成员列表
     */
    void createGroup(String groupId, String ownerId, String groupName, List<String> members);

    /**
     * 解散群组。
     * 只有群主可解散。
     */
    void disbandGroup(String groupId, String operatorId);

    /**
     * 修改群信息（名称/公告等）。
     */
    void setGroupInformation(String groupId, String groupName, String notification, String faceUrl);

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

    // ========== 加群申请流程 ==========

    /**
     * 申请加群。
     */
    void joinGroup(String groupId, String userId, String reqMsg);

    /**
     * 审批加群申请。
     *
     * @param agreed true=同意, false=拒绝
     */
    void respondJoinRequest(String groupId, String userId, String operatorId, String handleMsg, boolean agreed);

    /**
     * 获取待处理的加群申请列表。
     */
    List<GroupApply> getJoinRequests(String groupId);

    // ========== 查询 ==========

    /**
     * 获取群成员 ID 列表。
     *
     * @param groupId 群 ID
     * @return 成员 userId 集合（含群主），群不存在返回空集
     */
    Set<String> getMemberIds(String groupId);

    /**
     * 判断用户是否群成员。
     */
    boolean isMember(String groupId, String userId);

    /**
     * 用户加入的群列表。
     */
    Set<String> getJoinedGroups(String userId);

    /**
     * 获取群信息。
     */
    GroupInformation getGroupInformation(String groupId);

    /**
     * 获取群角色（owner/admin/member）。
     *
     * @return "owner" / "admin" / "member" / null（非成员）
     */
    String getRole(String groupId, String userId);
}

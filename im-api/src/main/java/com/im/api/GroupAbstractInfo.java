package com.im.api;

/**
 * 群抽象信息（成员数、在线数等汇总）。
 *
 * <p>用于群资料页的概要展示，不同于 {@link GroupInformation} 的基本资料，
 * 本类聚合了群的动态统计信息。</p>
 *
 * <p>对应 OpenIM 的 GetGroupAbstractInfo 返回值。</p>
 */
public class GroupAbstractInfo {

    private final String groupId;
    private final int memberCount;
    private final int onlineMemberCount;
    private final long createTime;

    public GroupAbstractInfo(String groupId, int memberCount, int onlineMemberCount, long createTime) {
        this.groupId = groupId;
        this.memberCount = memberCount;
        this.onlineMemberCount = onlineMemberCount;
        this.createTime = createTime;
    }

    /** 群 ID。 */
    public String getGroupId() { return groupId; }

    /** 总成员数（含群主/管理员）。 */
    public int getMemberCount() { return memberCount; }

    /** 当前在线的成员数。 */
    public int getOnlineMemberCount() { return onlineMemberCount; }

    /** 创建时间戳（毫秒）。 */
    public long getCreateTime() { return createTime; }
}

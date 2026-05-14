package com.im.core.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 群成员实体，映射 {@code im_group_members} 表。
 *
 * <p>对应 OpenIM {@code model.GroupMember}：用户在群里的角色、信息（群内昵称独立于用户主表）。</p>
 *
 * <p>RoleLevel 定义：1=普通成员, 100=管理员, 200=群主。</p>
 */
@TableName("im_group_members")
public class GroupMemberEntity {

    @TableField("id")
    private Long id;

    @TableField("group_id")
    private String groupId;

    @TableField("user_id")
    private String userId;

    @TableField("nickname")
    private String nickname;

    @TableField("face_url")
    private String faceUrl;

    @TableField("role_level")
    private int roleLevel;

    @TableField("join_source")
    private int joinSource;

    @TableField("inviter_user_id")
    private String inviterUserId;

    @TableField("operator_user_id")
    private String operatorUserId;

    @TableField("mute_end_time")
    private long muteEndTime;

    @TableField("ex")
    private String ex;

    @TableField("joined_at")
    private long joinedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getFaceUrl() { return faceUrl; }
    public void setFaceUrl(String faceUrl) { this.faceUrl = faceUrl; }

    public int getRoleLevel() { return roleLevel; }
    public void setRoleLevel(int roleLevel) { this.roleLevel = roleLevel; }

    public int getJoinSource() { return joinSource; }
    public void setJoinSource(int joinSource) { this.joinSource = joinSource; }

    public String getInviterUserId() { return inviterUserId; }
    public void setInviterUserId(String inviterUserId) { this.inviterUserId = inviterUserId; }

    public String getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(String operatorUserId) { this.operatorUserId = operatorUserId; }

    public long getMuteEndTime() { return muteEndTime; }
    public void setMuteEndTime(long muteEndTime) { this.muteEndTime = muteEndTime; }

    public String getEx() { return ex; }
    public void setEx(String ex) { this.ex = ex; }

    public long getJoinedAt() { return joinedAt; }
    public void setJoinedAt(long joinedAt) { this.joinedAt = joinedAt; }
}

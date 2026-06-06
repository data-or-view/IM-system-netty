package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.GroupMemberEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 群成员 Mapper。
 */
@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMemberEntity> {

    @Select("SELECT * FROM im_group_members WHERE group_id = #{groupId} AND role_level >= 100")
    List<GroupMemberEntity> selectAdmins(@Param("groupId") String groupId);

    @Select("SELECT * FROM im_group_members WHERE group_id = #{groupId} AND mute_end_time > UNIX_TIMESTAMP() * 1000")
    List<GroupMemberEntity> selectMutedMembers(@Param("groupId") String groupId);

    @Update("UPDATE im_group_members SET mute_end_time = #{muteEndTime} WHERE group_id = #{groupId} AND role_level < 100")
    int batchSetMuteEndTime(@Param("groupId") String groupId, @Param("muteEndTime") long muteEndTime);

    @Insert("""
            INSERT IGNORE INTO im_group_members
                (group_id, user_id, nickname, face_url, role_level, join_source, inviter_user_id,
                 operator_user_id, mute_end_time, ex, joined_at)
            VALUES
                (#{groupId}, #{userId}, '', '', #{roleLevel}, #{joinSource}, COALESCE(#{inviterUserId}, ''),
                 #{operatorUserId}, 0, '', #{joinedAt})
            """)
    int upsertMember(@Param("groupId") String groupId,
                     @Param("userId") String userId,
                     @Param("roleLevel") int roleLevel,
                     @Param("joinSource") int joinSource,
                     @Param("inviterUserId") String inviterUserId,
                     @Param("operatorUserId") String operatorUserId,
                     @Param("joinedAt") long joinedAt);
}

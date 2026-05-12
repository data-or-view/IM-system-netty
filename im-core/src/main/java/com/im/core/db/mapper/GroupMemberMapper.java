package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.GroupMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 群成员 Mapper。
 */
@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMemberEntity> {

    @Select("SELECT * FROM im_group_members WHERE group_id = #{groupId} AND role_level >= 100")
    List<GroupMemberEntity> selectAdmins(String groupId);

    @Select("SELECT * FROM im_group_members WHERE group_id = #{groupId} AND mute_end_time > UNIX_TIMESTAMP() * 1000")
    List<GroupMemberEntity> selectMutedMembers(String groupId);
}

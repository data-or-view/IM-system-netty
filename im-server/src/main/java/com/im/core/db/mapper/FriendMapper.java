package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.FriendEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 好友关系 Mapper。
 */
@Mapper
public interface FriendMapper extends BaseMapper<FriendEntity> {

    @Insert("""
            INSERT INTO im_friends
                (owner_user_id, friend_user_id, remark, add_source, operator_user_id, ex, is_pinned, created_at)
            VALUES
                (#{ownerUserId}, #{friendUserId}, '', #{addSource}, #{operatorUserId}, '', 0, #{createdAt})
            ON DUPLICATE KEY UPDATE
                add_source = VALUES(add_source),
                operator_user_id = VALUES(operator_user_id)
            """)
    int upsertFriend(@Param("ownerUserId") String ownerUserId,
                     @Param("friendUserId") String friendUserId,
                     @Param("addSource") int addSource,
                     @Param("operatorUserId") String operatorUserId,
                     @Param("createdAt") long createdAt);
}

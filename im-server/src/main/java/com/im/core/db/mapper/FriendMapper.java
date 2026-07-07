package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.api.FriendInformation;
import com.im.core.db.entity.FriendEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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

    @Select("""
            SELECT
                f.owner_user_id AS ownerUserId,
                f.friend_user_id AS friendUserId,
                u.nickname AS nickname,
                f.remark AS remark,
                u.face_url AS faceUrl,
                f.add_source AS addSourceCode,
                f.ex AS ex,
                f.is_pinned AS pinned,
                f.created_at AS createTime,
                0 AS deleted
            FROM im_friends f
            LEFT JOIN im_users u ON u.user_id = f.friend_user_id
            WHERE f.owner_user_id = #{ownerUserId}
            ORDER BY f.is_pinned DESC, f.created_at DESC, f.friend_user_id ASC
            """)
    List<FriendInformation> selectFriendInformationList(@Param("ownerUserId") String ownerUserId);

    @Select("""
            SELECT
                f.owner_user_id AS ownerUserId,
                f.friend_user_id AS friendUserId,
                u.nickname AS nickname,
                f.remark AS remark,
                u.face_url AS faceUrl,
                f.add_source AS addSourceCode,
                f.ex AS ex,
                f.is_pinned AS pinned,
                f.created_at AS createTime,
                0 AS deleted
            FROM im_friends f
            LEFT JOIN im_users u ON u.user_id = f.friend_user_id
            WHERE f.owner_user_id = #{ownerUserId}
              AND f.friend_user_id = #{friendUserId}
            """)
    FriendInformation selectFriendInformation(@Param("ownerUserId") String ownerUserId,
                                              @Param("friendUserId") String friendUserId);
}

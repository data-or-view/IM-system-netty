package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.api.SystemMessageInboxItem;
import com.im.core.db.entity.SystemMessageInboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SystemMessageInboxMapper extends BaseMapper<SystemMessageInboxEntity> {

    @Insert("""
            INSERT IGNORE INTO im_system_message_inbox
                (message_id, user_id, channel_id, read_at, deleted, archived, created_at)
            VALUES
                (#{messageId}, #{userId}, #{channelId}, 0, 0, 0, #{createdAt})
            """)
    int insertIgnore(@Param("messageId") String messageId,
                     @Param("userId") String userId,
                     @Param("channelId") String channelId,
                     @Param("createdAt") long createdAt);

    @Select("""
            <script>
            SELECT i.message_id AS messageId, i.user_id AS userId, i.channel_id AS channelId,
                   c.channel_name AS channelName, m.title, m.summary, m.content, m.content_type AS contentType,
                   m.priority, m.created_at AS createdAt, i.read_at AS readAt,
                   i.deleted = 1 AS deleted, i.archived = 1 AS archived
            FROM im_system_message_inbox i
            JOIN im_system_messages m ON m.message_id = i.message_id
            LEFT JOIN im_system_channels c ON c.channel_id = i.channel_id
            WHERE i.user_id = #{userId}
              AND i.deleted = 0
              <if test="channelId != null and channelId != ''">AND i.channel_id = #{channelId}</if>
              <if test="onlyUnread">AND i.read_at = 0</if>
              <if test="cursor &gt; 0">AND i.created_at &lt; #{cursor}</if>
              AND (m.expire_at = 0 OR m.expire_at &gt; #{now})
            ORDER BY i.created_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<SystemMessageInboxItem> selectInbox(@Param("userId") String userId,
                                             @Param("channelId") String channelId,
                                             @Param("onlyUnread") boolean onlyUnread,
                                             @Param("limit") int limit,
                                             @Param("cursor") long cursor,
                                             @Param("now") long now);

    @Select("""
            SELECT i.message_id AS messageId, i.user_id AS userId, i.channel_id AS channelId,
                   c.channel_name AS channelName, m.title, m.summary, m.content, m.content_type AS contentType,
                   m.priority, m.created_at AS createdAt, i.read_at AS readAt,
                   i.deleted = 1 AS deleted, i.archived = 1 AS archived
            FROM im_system_message_inbox i
            JOIN im_system_messages m ON m.message_id = i.message_id
            LEFT JOIN im_system_channels c ON c.channel_id = i.channel_id
            WHERE i.user_id = #{userId} AND i.message_id = #{messageId} AND i.deleted = 0
            LIMIT 1
            """)
    SystemMessageInboxItem selectDetail(@Param("userId") String userId,
                                        @Param("messageId") String messageId);

    @Update("""
            UPDATE im_system_message_inbox
            SET read_at = #{readAt}
            WHERE user_id = #{userId} AND message_id = #{messageId} AND read_at = 0
            """)
    int markRead(@Param("userId") String userId, @Param("messageId") String messageId, @Param("readAt") long readAt);

    @Update("""
            <script>
            UPDATE im_system_message_inbox
            SET read_at = #{readAt}
            WHERE user_id = #{userId} AND read_at = 0 AND deleted = 0
              <if test="channelId != null and channelId != ''">AND channel_id = #{channelId}</if>
            </script>
            """)
    int markAllRead(@Param("userId") String userId, @Param("channelId") String channelId, @Param("readAt") long readAt);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM im_system_message_inbox
            WHERE user_id = #{userId} AND read_at = 0 AND deleted = 0
              <if test="channelId != null and channelId != ''">AND channel_id = #{channelId}</if>
            </script>
            """)
    int unreadCount(@Param("userId") String userId, @Param("channelId") String channelId);

    @Select("""
            SELECT channel_id AS channelId, COUNT(*) AS count
            FROM im_system_message_inbox
            WHERE user_id = #{userId} AND read_at = 0 AND deleted = 0
            GROUP BY channel_id
            """)
    List<Map<String, Object>> unreadCountByChannel(@Param("userId") String userId);
}

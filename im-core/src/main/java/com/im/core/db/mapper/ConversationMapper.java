package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.ConversationEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 会话 Mapper。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    @Select("SELECT * FROM im_conversations WHERE owner_user_id = #{userId} ORDER BY is_pinned DESC, updated_at DESC")
    List<ConversationEntity> selectByUserOrdered(String userId);

    @Select("SELECT * FROM im_conversations WHERE owner_user_id = #{userId} AND conversation_id = #{conversationId}")
    ConversationEntity selectByUserAndConversation(@Param("userId") String userId, @Param("conversationId") String conversationId);

    @Update("UPDATE im_conversations SET max_seq = #{seq}, updated_at = #{time} " +
            "WHERE owner_user_id = #{userId} AND conversation_id = #{conversationId}")
    int updateMaxSeq(@Param("userId") String userId,
                     @Param("conversationId") String conversationId,
                     @Param("seq") long seq,
                     @Param("time") long time);

    @Insert("INSERT INTO im_conversations (owner_user_id, conversation_id, conversation_type, " +
            "user_id, group_id, attached_info, max_seq, unread_count, updated_at) " +
            "VALUES (#{ownerUserId}, #{conversationId}, #{convType}, #{userId}, #{groupId}, " +
            "#{attachedInfo}, #{newSeq}, 0, #{now}) " +
            "ON DUPLICATE KEY UPDATE " +
            "max_seq = VALUES(max_seq), " +
            "attached_info = VALUES(attached_info), " +
            "updated_at = VALUES(updated_at)")
    int upsertConversation(@Param("ownerUserId") String ownerUserId,
                           @Param("conversationId") String conversationId,
                           @Param("convType") int convType,
                           @Param("userId") String userId,
                           @Param("groupId") String groupId,
                           @Param("attachedInfo") String attachedInfo,
                           @Param("newSeq") long newSeq,
                           @Param("now") long now);

    @Update("UPDATE im_conversations SET unread_count = unread_count + 1 " +
            "WHERE owner_user_id = #{userId} AND conversation_id = #{conversationId}")
    int incrementUnread(@Param("userId") String ownerUserId,
                        @Param("conversationId") String conversationId);

    @Update("UPDATE im_conversations SET unread_count = 0 " +
            "WHERE owner_user_id = #{userId} AND conversation_id = #{conversationId}")
    int resetUnread(@Param("userId") String ownerUserId,
                    @Param("conversationId") String conversationId);

    @Update("UPDATE im_conversations SET updated_at = #{time} " +
            "WHERE owner_user_id = #{userId} AND conversation_id = #{conversationId}")
    int updateUpdatedAt(@Param("userId") String ownerUserId,
                        @Param("conversationId") String conversationId,
                        @Param("time") long time);
}

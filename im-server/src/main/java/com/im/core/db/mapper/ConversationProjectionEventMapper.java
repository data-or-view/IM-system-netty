package com.im.core.db.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Durable inbound conversation projection operations. */
@Mapper
public interface ConversationProjectionEventMapper {

    @Insert("INSERT IGNORE INTO im_conversation_projection_events "
            + "(owner_user_id, conversation_id, message_id, message_seq, created_at) "
            + "VALUES (#{ownerUserId}, #{conversationId}, #{messageId}, #{messageSeq}, #{now})")
    int insertInboundIfAbsent(@Param("ownerUserId") String ownerUserId,
                              @Param("conversationId") String conversationId,
                              @Param("messageId") String messageId,
                              @Param("messageSeq") long messageSeq,
                              @Param("now") long now);

    @Select("SELECT COUNT(*) FROM im_conversation_projection_events e "
            + "LEFT JOIN im_message_read_states r "
            + "ON r.user_id = e.owner_user_id AND r.conversation_id = e.conversation_id "
            + "WHERE e.owner_user_id = #{ownerUserId} AND e.conversation_id = #{conversationId} "
            + "AND e.message_seq > COALESCE(r.read_seq, 0)")
    long countUnreadAfter(@Param("ownerUserId") String ownerUserId,
                          @Param("conversationId") String conversationId);
}

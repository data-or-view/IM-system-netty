package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.MessageReadStateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for user-level message read states.
 */
@Mapper
public interface MessageReadStateMapper extends BaseMapper<MessageReadStateEntity> {

    @Select("SELECT * FROM im_message_read_states WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    MessageReadStateEntity selectByUserConversation(@Param("userId") String userId,
                                                    @Param("conversationId") String conversationId);

    @Update("""
            INSERT INTO im_message_read_states
                (user_id, conversation_id, read_seq, delivered_seq, unread_count, updated_at)
            VALUES
                (#{userId}, #{conversationId}, #{readSeq}, #{deliveredSeq}, #{unreadCount}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                read_seq = GREATEST(read_seq, VALUES(read_seq)),
                delivered_seq = GREATEST(delivered_seq, VALUES(delivered_seq)),
                unread_count = VALUES(unread_count),
                updated_at = VALUES(updated_at)
            """)
    int upsertState(@Param("userId") String userId,
                    @Param("conversationId") String conversationId,
                    @Param("readSeq") long readSeq,
                    @Param("deliveredSeq") long deliveredSeq,
                    @Param("unreadCount") int unreadCount,
                    @Param("updatedAt") long updatedAt);
}

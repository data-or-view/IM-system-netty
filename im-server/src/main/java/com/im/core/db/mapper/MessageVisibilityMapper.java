package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.MessageVisibilityEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for user-level message visibility states.
 */
@Mapper
public interface MessageVisibilityMapper extends BaseMapper<MessageVisibilityEntity> {

    @Update("""
            INSERT INTO im_message_visibility
                (user_id, conversation_id, seq, client_msg_id, visibility_state, operator_user_id, reason, updated_at)
            VALUES
                (#{userId}, #{conversationId}, #{seq}, #{clientMsgId}, #{visibilityState}, #{operatorUserId}, #{reason}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                client_msg_id = VALUES(client_msg_id),
                visibility_state = VALUES(visibility_state),
                operator_user_id = VALUES(operator_user_id),
                reason = VALUES(reason),
                updated_at = VALUES(updated_at)
            """)
    int upsertVisibility(@Param("userId") String userId,
                         @Param("conversationId") String conversationId,
                         @Param("seq") long seq,
                         @Param("clientMsgId") String clientMsgId,
                         @Param("visibilityState") int visibilityState,
                         @Param("operatorUserId") String operatorUserId,
                         @Param("reason") String reason,
                         @Param("updatedAt") long updatedAt);
}

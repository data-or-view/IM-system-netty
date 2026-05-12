package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}

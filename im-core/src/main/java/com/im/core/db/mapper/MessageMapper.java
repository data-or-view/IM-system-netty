package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 消息 Mapper。
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    @Select("SELECT * FROM im_messages WHERE conversation_id = #{conversationId} ORDER BY seq DESC LIMIT #{limit}")
    List<MessageEntity> selectRecent(@Param("conversationId") String conversationId, @Param("limit") int limit);

    @Select("SELECT * FROM im_messages WHERE conversation_id = #{conversationId} AND seq < #{seq} ORDER BY seq DESC LIMIT #{limit}")
    List<MessageEntity> selectHistory(@Param("conversationId") String conversationId,
                                      @Param("seq") long seq,
                                      @Param("limit") int limit);

    @Select("SELECT * FROM im_messages WHERE conversation_id = #{conversationId} AND seq = #{seq}")
    MessageEntity selectBySeq(@Param("conversationId") String conversationId, @Param("seq") long seq);

    @Select("SELECT * FROM im_messages WHERE conversation_id = #{conversationId} AND seq BETWEEN #{from} AND #{to} ORDER BY seq ASC")
    List<MessageEntity> selectBySeqRange(@Param("conversationId") String conversationId,
                                          @Param("from") long from,
                                          @Param("to") long to);

    @Update("UPDATE im_messages SET revoke_user_id = #{revokerId}, revoke_role = #{role}, " +
            "revoke_nickname = #{nickname}, revoke_time = #{time}, status = 1 " +
            "WHERE conversation_id = #{conversationId} AND seq = #{seq}")
    int revokeMessage(@Param("conversationId") String conversationId,
                      @Param("seq") long seq,
                      @Param("revokerId") String revokerId,
                      @Param("role") int role,
                      @Param("nickname") String nickname,
                      @Param("time") long time);
}

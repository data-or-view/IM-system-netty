package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.SeqUserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户序号 Mapper。
 */
@Mapper
public interface SeqUserMapper extends BaseMapper<SeqUserEntity> {

    @Select("SELECT * FROM im_seq_users WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    SeqUserEntity selectByUserAndConversation(@Param("userId") String userId,
                                              @Param("conversationId") String conversationId);

    @Update("UPDATE im_seq_users SET read_seq = GREATEST(read_seq, #{readSeq}), updated_at = #{time} " +
            "WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    int updateReadSeq(@Param("userId") String userId,
                      @Param("conversationId") String conversationId,
                      @Param("readSeq") long readSeq,
                      @Param("time") long time);
}

package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.SeqUserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户序号 Mapper。
 */
@Mapper
public interface SeqUserMapper extends BaseMapper<SeqUserEntity> {

    @Insert("INSERT INTO im_seq_users "
            + "(user_id, conversation_id, min_seq, max_seq, read_seq, updated_at) "
            + "VALUES (#{userId}, #{conversationId}, #{messageSeq}, #{messageSeq}, 0, #{time}) "
            + "ON DUPLICATE KEY UPDATE "
            + "min_seq = LEAST(min_seq, VALUES(min_seq)), "
            + "updated_at = CASE WHEN VALUES(max_seq) > max_seq THEN VALUES(updated_at) ELSE updated_at END, "
            + "max_seq = GREATEST(max_seq, VALUES(max_seq))")
    int upsertMaxSeq(@Param("userId") String userId,
                     @Param("conversationId") String conversationId,
                     @Param("messageSeq") long messageSeq,
                     @Param("time") long time);

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

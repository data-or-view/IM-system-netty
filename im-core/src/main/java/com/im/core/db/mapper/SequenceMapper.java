package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.SequenceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会话序号 Mapper。
 *
 * <p>序号发生器：原子递增获取 seq。</p>
 * <pre>
 * // 获取下一个 seq
 * sequenceMapper.incrementMaxSeq("conv_abc", System.currentTimeMillis());
 * SequenceEntity seq = sequenceMapper.selectById("conv_abc");
 * long newSeq = seq.getMaxSeq();
 * </pre>
 */
@Mapper
public interface SequenceMapper extends BaseMapper<SequenceEntity> {

    @Update("INSERT INTO im_sequences (conversation_id, max_seq, min_seq, updated_at) " +
            "VALUES (#{conversationId}, 1, 0, #{now}) " +
            "ON DUPLICATE KEY UPDATE max_seq = max_seq + 1, updated_at = #{now}")
    int incrementMaxSeq(@Param("conversationId") String conversationId, @Param("now") long now);
}

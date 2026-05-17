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

    @Select("<script>" +
            "SELECT * FROM im_messages WHERE status = 0 " +
            "<if test='conversationIds != null and !conversationIds.isEmpty()'> " +
            "AND conversation_id IN " +
            "<foreach item='cid' collection='conversationIds' open='(' separator=',' close=')'>#{cid}</foreach> " +
            "</if> " +
            "<if test='keyword != null and !keyword.isEmpty()'>AND content LIKE CONCAT('%', #{keyword}, '%') </if> " +
            "<if test='contentTypes != null and !contentTypes.isEmpty()'>AND content_type IN " +
            "<foreach item='ct' collection='contentTypes' open='(' separator=',' close=')'>#{ct}</foreach> " +
            "</if> " +
            "<if test='senderId != null and !senderId.isEmpty()'>AND send_id = #{senderId} </if> " +
            "<if test='startTime != null'>AND sent_at &gt;= #{startTime} </if> " +
            "<if test='endTime != null'>AND sent_at &lt;= #{endTime} </if> " +
            "ORDER BY sent_at DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<MessageEntity> selectByKeyword(@Param("conversationIds") List<String> conversationIds,
                                         @Param("keyword") String keyword,
                                         @Param("contentTypes") List<Integer> contentTypes,
                                         @Param("senderId") String senderId,
                                         @Param("startTime") Long startTime,
                                         @Param("endTime") Long endTime,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM im_messages WHERE status = 0 " +
            "<if test='conversationIds != null and !conversationIds.isEmpty()'> " +
            "AND conversation_id IN " +
            "<foreach item='cid' collection='conversationIds' open='(' separator=',' close=')'>#{cid}</foreach> " +
            "</if> " +
            "<if test='keyword != null and !keyword.isEmpty()'>AND content LIKE CONCAT('%', #{keyword}, '%') </if> " +
            "<if test='contentTypes != null and !contentTypes.isEmpty()'>AND content_type IN " +
            "<foreach item='ct' collection='contentTypes' open='(' separator=',' close=')'>#{ct}</foreach> " +
            "</if> " +
            "<if test='senderId != null and !senderId.isEmpty()'>AND send_id = #{senderId} </if> " +
            "<if test='startTime != null'>AND sent_at &gt;= #{startTime} </if> " +
            "<if test='endTime != null'>AND sent_at &lt;= #{endTime} </if> " +
            "</script>")
    long countByKeyword(@Param("conversationIds") List<String> conversationIds,
                        @Param("keyword") String keyword,
                        @Param("contentTypes") List<Integer> contentTypes,
                        @Param("senderId") String senderId,
                        @Param("startTime") Long startTime,
                        @Param("endTime") Long endTime);
}

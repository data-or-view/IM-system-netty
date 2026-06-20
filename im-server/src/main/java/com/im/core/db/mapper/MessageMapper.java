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

    @Select("""
            SELECT m.* FROM im_messages m
            LEFT JOIN im_message_read_states rs
              ON rs.user_id = #{userId}
             AND rs.conversation_id = m.conversation_id
            WHERE m.recv_id = #{userId}
              AND m.status = 0
              AND m.seq > COALESCE(rs.delivered_seq, 0)
              AND NOT EXISTS (
                  SELECT 1 FROM im_message_visibility v
                  WHERE v.user_id = #{userId}
                    AND v.conversation_id = m.conversation_id
                    AND v.seq = m.seq
                    AND v.visibility_state <> 0
              )
            ORDER BY m.sent_at ASC
            LIMIT #{limit}
            """)
    List<MessageEntity> selectUndeliveredSingleMessages(@Param("userId") String userId,
                                                        @Param("limit") int limit);

    @Select("<script>" +
            "SELECT * FROM im_messages WHERE client_msg_id IN " +
            "<foreach item='id' collection='clientMsgIds' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<MessageEntity> selectByClientMsgIds(@Param("clientMsgIds") List<String> clientMsgIds);

    @Select("""
            SELECT * FROM im_messages
            WHERE recv_id = #{userId}
              AND seq < #{seq}
              AND status = 0
              AND NOT EXISTS (
                  SELECT 1 FROM im_message_visibility v
                  WHERE v.user_id = #{userId}
                    AND v.conversation_id = im_messages.conversation_id
                    AND v.seq = im_messages.seq
                    AND v.visibility_state <> 0
              )
            """)
    List<MessageEntity> selectSingleMessagesBefore(@Param("userId") String userId, @Param("seq") long seq);

    @Update("UPDATE im_messages SET revoke_user_id = #{revokerId}, revoke_role = #{role}, " +
            "revoke_nickname = #{nickname}, revoke_time = #{time}, status = 1 " +
            "WHERE conversation_id = #{conversationId} AND seq = #{seq} AND status = 0 " +
            "AND (send_id = #{revokerId} OR #{role} >= 100)")
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

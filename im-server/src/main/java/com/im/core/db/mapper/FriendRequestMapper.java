package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.FriendRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 好友申请 Mapper。
 */
@Mapper
public interface FriendRequestMapper extends BaseMapper<FriendRequestEntity> {

    @Insert("""
            INSERT INTO im_friend_requests
                (from_user_id, to_user_id, handle_result, req_msg, handler_user_id, handle_msg, handle_time, ex, created_at)
            VALUES
                (#{fromUserId}, #{toUserId}, #{handleResult}, #{reqMsg}, '', '', 0, '', #{createdAt})
            ON DUPLICATE KEY UPDATE
                handle_result = VALUES(handle_result),
                req_msg = VALUES(req_msg),
                handler_user_id = '',
                handle_msg = '',
                handle_time = 0,
                created_at = VALUES(created_at)
            """)
    int upsertPendingApply(@Param("fromUserId") String fromUserId,
                           @Param("toUserId") String toUserId,
                           @Param("handleResult") int handleResult,
                           @Param("reqMsg") String reqMsg,
                           @Param("createdAt") long createdAt);
}

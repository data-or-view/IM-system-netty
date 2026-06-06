package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.GroupRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 加群申请 Mapper。
 */
@Mapper
public interface GroupRequestMapper extends BaseMapper<GroupRequestEntity> {

    @Insert("""
            INSERT INTO im_group_requests
                (user_id, group_id, handle_result, req_msg, handled_msg, handler_user_id,
                 handled_time, join_source, inviter_user_id, ex, created_at)
            VALUES
                (#{userId}, #{groupId}, #{handleResult}, #{reqMsg}, '', '', 0,
                 #{joinSource}, '', '', #{createdAt})
            ON DUPLICATE KEY UPDATE
                handle_result = VALUES(handle_result),
                req_msg = VALUES(req_msg),
                handled_msg = '',
                handler_user_id = '',
                handled_time = 0,
                join_source = VALUES(join_source),
                created_at = VALUES(created_at)
            """)
    int upsertPendingApply(@Param("groupId") String groupId,
                           @Param("userId") String userId,
                           @Param("handleResult") int handleResult,
                           @Param("reqMsg") String reqMsg,
                           @Param("joinSource") int joinSource,
                           @Param("createdAt") long createdAt);
}

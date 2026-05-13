package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户 Mapper。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("SELECT * FROM im_users WHERE status = 1")
    List<UserEntity> selectOnlineUsers();

    @Select("SELECT * FROM im_users WHERE nickname LIKE CONCAT('%', #{keyword}, '%')")
    List<UserEntity> searchByNickname(String keyword);

    /**
     * 同时按昵称和 user_id 模糊搜索，按相关度排序。
     * user_id 精确匹配优先，然后 nickname 模糊匹配，最后 user_id 模糊匹配。
     */
    @Select({
        "(SELECT * FROM im_users WHERE user_id = #{keyword} AND status = 1) " +
        "UNION " +
        "(SELECT * FROM im_users WHERE nickname LIKE CONCAT('%', #{keyword}, '%') AND status = 1 AND user_id != #{keyword}) " +
        "UNION " +
        "(SELECT * FROM im_users WHERE user_id LIKE CONCAT('%', #{keyword}, '%') AND status = 1 AND user_id != #{keyword} AND nickname NOT LIKE CONCAT('%', #{keyword}, '%')) " +
        "LIMIT #{limit}"
    })
    List<UserEntity> searchByKeyword(String keyword, int limit);
}

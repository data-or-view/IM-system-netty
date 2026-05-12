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
}

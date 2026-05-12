package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户 Mapper（示例）。
 *
 * <p>继承 {@link BaseMapper} 后，自动拥有 insert / deleteById / updateById / selectById / selectList 等 CRUD 方法。
 * 复杂查询用 {@code @Select} / {@code @Update} 等注解或 XML 手写 SQL。</p>
 *
 * <pre>
 *     UserMapper mapper = session.getMapper(UserMapper.class);
 *
 *     // CRUD 模板（自动生成）
 *     mapper.insert(user);
 *     mapper.selectById("alice");
 *     mapper.updateById(user);
 *
 *     // 手写 SQL
 *     List&lt;UserEntity&gt; online = mapper.selectOnlineUsers();
 * </pre>
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /**
     * 查询所有在线用户。
     *
     * @return 在线用户列表（status = 1）
     */
    @Select("SELECT * FROM im_users WHERE status = 1")
    List<UserEntity> selectOnlineUsers();

    /**
     * 根据昵称模糊搜索用户。
     *
     * @param keyword 搜索关键词
     * @return 匹配的用户列表
     */
    @Select("SELECT * FROM im_users WHERE nickname LIKE CONCAT('%', #{keyword}, '%')")
    List<UserEntity> searchByNickname(String keyword);
}

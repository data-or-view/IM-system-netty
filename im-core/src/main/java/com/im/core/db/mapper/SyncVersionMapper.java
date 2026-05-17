package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.SyncVersionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 增量同步版本计数器 Mapper。
 */
@Mapper
public interface SyncVersionMapper extends BaseMapper<SyncVersionEntity> {

    /**
     * 原子递增版本号，未存在则插入初始值 1。
     */
    @Insert("INSERT INTO im_sync_versions (user_id, entity_type, version) " +
            "VALUES (#{userId}, #{entityType}, 1) " +
            "ON DUPLICATE KEY UPDATE version = version + 1")
    int incrementVersion(@Param("userId") String userId, @Param("entityType") String entityType);

    /**
     * 获取当前版本号，不存在返回 0。
     */
    @Select("SELECT COALESCE(version, 0) FROM im_sync_versions " +
            "WHERE user_id = #{userId} AND entity_type = #{entityType}")
    long getVersion(@Param("userId") String userId, @Param("entityType") String entityType);
}

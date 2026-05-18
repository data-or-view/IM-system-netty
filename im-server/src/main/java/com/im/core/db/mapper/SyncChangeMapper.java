package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.SyncChangeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 增量同步变更日志 Mapper。
 */
@Mapper
public interface SyncChangeMapper extends BaseMapper<SyncChangeEntity> {

    /**
     * 查询指定用户+实体类型在某版本后的变更记录。
     */
    @Select("SELECT * FROM im_sync_changes " +
            "WHERE user_id = #{userId} AND entity_type = #{entityType} AND version > #{sinceVersion} " +
            "ORDER BY version ASC LIMIT #{limit}")
    List<SyncChangeEntity> selectChangesSince(@Param("userId") String userId,
                                              @Param("entityType") String entityType,
                                              @Param("sinceVersion") long sinceVersion,
                                              @Param("limit") int limit);

    /**
     * 插入变更记录。
     */
    @Insert("INSERT INTO im_sync_changes (user_id, entity_type, entity_id, version, action, created_at) " +
            "VALUES (#{userId}, #{entityType}, #{entityId}, #{version}, #{action}, #{createdAt})")
    int insertChange(SyncChangeEntity entity);
}

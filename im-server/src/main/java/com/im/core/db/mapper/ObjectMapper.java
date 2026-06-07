package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.ObjectEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 文件上传元数据 Mapper。
 */
@Mapper
public interface ObjectMapper extends BaseMapper<ObjectEntity> {

    @Select("SELECT * FROM im_objects WHERE hash = #{hash} LIMIT 1")
    ObjectEntity selectByHash(String hash);

    @Select("SELECT * FROM im_objects WHERE name = #{fileId} LIMIT 1")
    ObjectEntity selectByFileId(String fileId);

    @Select("SELECT * FROM im_objects WHERE user_id = #{userId} ORDER BY created_at DESC")
    java.util.List<ObjectEntity> selectByUser(String userId);
}

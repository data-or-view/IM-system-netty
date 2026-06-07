package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.SystemChannelEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemChannelMapper extends BaseMapper<SystemChannelEntity> {

    @Insert("""
            INSERT INTO im_system_channels
                (channel_id, channel_name, channel_type, description, status, created_at, updated_at)
            VALUES
                (#{channelId}, #{channelName}, #{channelType}, #{description}, #{status}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE channel_id = channel_id
            """)
    int insertIfAbsent(SystemChannelEntity entity);
}

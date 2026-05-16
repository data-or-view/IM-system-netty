package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.BlacklistEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 黑名单 Mapper。
 */
@Mapper
public interface BlacklistMapper extends BaseMapper<BlacklistEntity> {
}

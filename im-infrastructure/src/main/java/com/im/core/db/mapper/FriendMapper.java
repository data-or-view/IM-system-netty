package com.im.core.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.db.entity.FriendEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 好友关系 Mapper。
 */
@Mapper
public interface FriendMapper extends BaseMapper<FriendEntity> {
}

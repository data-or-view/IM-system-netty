package com.wzg.idempotency.persistence.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordEntity> {

    @Select("SELECT * FROM im_idempotency_records WHERE idempotency_key = #{idempotencyKey}")
    IdempotencyRecordEntity selectByKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM im_idempotency_records WHERE idempotency_key = #{idempotencyKey} FOR UPDATE")
    IdempotencyRecordEntity selectByKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO im_idempotency_records (
              idempotency_key, status, expiry_timestamp, in_progress_expiry_timestamp,
              response_data, payload_hash, created_at, updated_at
            ) VALUES (
              #{record.idempotencyKey}, #{record.status}, #{record.expiryTimestamp},
              #{record.inProgressExpiryTimestamp}, #{record.responseData}, #{record.payloadHash},
              #{record.createdAt}, #{record.updatedAt}
            )
            """)
    int insertRecord(@Param("record") IdempotencyRecordEntity record);

    @Update("""
            UPDATE im_idempotency_records
            SET status = #{record.status},
                expiry_timestamp = #{record.expiryTimestamp},
                in_progress_expiry_timestamp = #{record.inProgressExpiryTimestamp},
                response_data = #{record.responseData},
                payload_hash = #{record.payloadHash},
                updated_at = #{record.updatedAt}
            WHERE idempotency_key = #{record.idempotencyKey}
            """)
    int updateRecord(@Param("record") IdempotencyRecordEntity record);

    @Delete("DELETE FROM im_idempotency_records WHERE idempotency_key = #{idempotencyKey}")
    int deleteByKey(@Param("idempotencyKey") String idempotencyKey);
}

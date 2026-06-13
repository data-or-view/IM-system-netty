package com.wzg.idempotency.persistence.mybatis;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("im_idempotency_records")
public class IdempotencyRecordEntity {

    @TableId("idempotency_key")
    private String idempotencyKey;

    @TableField("status")
    private String status;

    @TableField("expiry_timestamp")
    private long expiryTimestamp;

    @TableField("in_progress_expiry_timestamp")
    private long inProgressExpiryTimestamp;

    @TableField("response_data")
    private String responseData;

    @TableField("payload_hash")
    private String payloadHash;

    @TableField("created_at")
    private long createdAt;

    @TableField("updated_at")
    private long updatedAt;

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getExpiryTimestamp() {
        return expiryTimestamp;
    }

    public void setExpiryTimestamp(long expiryTimestamp) {
        this.expiryTimestamp = expiryTimestamp;
    }

    public long getInProgressExpiryTimestamp() {
        return inProgressExpiryTimestamp;
    }

    public void setInProgressExpiryTimestamp(long inProgressExpiryTimestamp) {
        this.inProgressExpiryTimestamp = inProgressExpiryTimestamp;
    }

    public String getResponseData() {
        return responseData;
    }

    public void setResponseData(String responseData) {
        this.responseData = responseData;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}

package com.wzg.idempotency.persistence;

/**
 * 幂等记录状态。
 */
public enum DataRecordStatus {
    INPROGRESS("INPROGRESS"),
    COMPLETED("COMPLETED"),
    EXPIRED("EXPIRED");

    private final String status;

    DataRecordStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return status;
    }
}

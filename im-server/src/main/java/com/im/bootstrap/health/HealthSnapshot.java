package com.im.bootstrap.health;

import java.time.Instant;
import java.util.Map;

public record HealthSnapshot(
        HealthStatus status,
        String nodeId,
        Instant checkedAt,
        Map<String, HealthStatus> checks
) {
    public static HealthSnapshot live(String nodeId) {
        return new HealthSnapshot(HealthStatus.UP, nodeId, Instant.now(), Map.of("process", HealthStatus.UP));
    }

    public static HealthSnapshot ready(String nodeId, boolean requestAdmissionOpen) {
        HealthStatus admission = requestAdmissionOpen ? HealthStatus.UP : HealthStatus.DOWN;
        return new HealthSnapshot(admission, nodeId, Instant.now(), Map.of(
                "requestAdmission", admission
        ));
    }
}

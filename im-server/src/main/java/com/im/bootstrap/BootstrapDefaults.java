package com.im.bootstrap;

import com.im.api.MultiLoginStrategy;

import java.time.Duration;

final class BootstrapDefaults {
    static final String NODE_ID = "node-1";
    static final String LOOPBACK_HOST = "127.0.0.1";
    static final String LOCALHOST_NAME = "localhost";
    static final int WS_PORT = 8081;
    static final int HTTP_PORT = 8082;
    static final int REDIS_PORT = 6379;
    static final String CORS_ALLOWED_ORIGINS = "http://127.0.0.1:39073,http://localhost:39073";
    static final String LIVEKIT_SFU_ENDPOINT = "ws://localhost:7880";
    static final String MINIO_ENDPOINT = "http://127.0.0.1:9000";
    static final String MULTI_LOGIN_STRATEGY = MultiLoginStrategy.ALLOW_MULTIPLE.name();
    static final long SEND_IDEMPOTENCY_TTL_SECONDS = 24 * 60 * 60L;
    static final Duration REQUEST_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private BootstrapDefaults() {
    }
}

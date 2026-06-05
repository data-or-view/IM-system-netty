package com.im.api;

/**
 * Protocol-level header names shared by HTTP and WebSocket adapters.
 */
public final class ImHeaders {

    public static final String AUTHORIZATION = "Authorization";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String TRACE_ID = "X-Trace-Id";
    public static final String TRACEPARENT = "traceparent";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String APPLICATION_JSON = "application/json";

    private ImHeaders() {
    }
}

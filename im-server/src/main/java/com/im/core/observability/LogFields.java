package com.im.core.observability;

public final class LogFields {

    public static final String EVENT = "event";
    public static final String NODE_ID = "nodeId";
    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String TARGET_USER_ID = "targetUserId";
    public static final String OPERATION = "operation";
    public static final String PROTOCOL = "protocol";
    public static final String CLIENT_IP = "clientIp";
    public static final String CONNECTION_ID = "connectionId";
    public static final String WS_SEQ = "wsSeq";
    public static final String HTTP_METHOD = "httpMethod";
    public static final String HTTP_PATH = "httpPath";
    public static final String LATENCY_MS = "latencyMs";
    public static final String SUCCESS = "success";
    public static final String STATUS = "status";
    public static final String ERROR_CODE = "errorCode";
    public static final String EXCEPTION_CLASS = "exceptionClass";
    public static final String DETAIL = "detail";
    public static final String REASON = "reason";
    public static final String HANDLER = "handler";
    public static final String INTERCEPTOR = "interceptor";
    public static final String RULE = "rule";
    public static final String KEY = "key";
    public static final String LIMIT = "limit";
    public static final String WINDOW_MS = "windowMs";
    public static final String CURRENT_COUNT = "currentCount";
    public static final String REMAINING = "remaining";
    public static final String RETRY_AFTER_SECONDS = "retryAfterSeconds";
    public static final String FAIL_OPEN = "failOpen";
    public static final String TOPIC = "topic";
    public static final String STREAM_ID = "streamId";
    public static final String CONSUMER_ID = "consumerId";
    public static final String MESSAGE_ID = "messageId";
    public static final String CLIENT_MSG_ID = "clientMsgId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String GROUP_ID = "groupId";
    public static final String FROM_USER_ID = "fromUserId";
    public static final String TO_USER_ID = "toUserId";
    public static final String MESSAGE_SEQ = "messageSeq";
    public static final String SEQUENCE_ID = "sequenceId";
    public static final String ROUTE_COUNT = "routeCount";
    public static final String SESSION_ID = "sessionId";
    public static final String PLATFORM_ID = "platformId";
    public static final String SOURCE_NODE_ID = "sourceNodeId";
    public static final String TARGET_NODE_ID = "targetNodeId";
    public static final String DELIVERED_COUNT = "deliveredCount";
    public static final String TARGET_COUNT = "targetCount";
    public static final String FILE_ID = "fileId";
    public static final String FILE_SIZE = "fileSize";
    public static final String FILE_GROUP = "fileGroup";
    public static final String UPLOAD_ID = "uploadId";
    public static final String PART_NUMBER = "partNumber";
    public static final String PART_SIZE = "partSize";

    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_USER_ID = "app.user.id";
    public static final String MDC_OPERATION = "app.operation";
    public static final String MDC_PROTOCOL = "app.protocol";
    public static final String MDC_NODE_ID = "node_id";
    public static final String MDC_CLIENT_IP = "client_ip";
    public static final String MDC_CONNECTION_ID = "connection_id";
    public static final String MDC_WS_SEQ = "ws.seq";
    public static final String MDC_MESSAGE_ID = "message_id";
    public static final String MDC_CLIENT_MSG_ID = "client_msg_id";
    public static final String MDC_CONVERSATION_ID = "conversation_id";
    public static final String MDC_GROUP_ID = "group_id";
    public static final String MDC_FROM_USER_ID = "from_user_id";
    public static final String MDC_TO_USER_ID = "to_user_id";
    public static final String MDC_TOPIC = "mq.topic";
    public static final String MDC_STREAM_ID = "mq.stream.id";
    public static final String MDC_BUSINESS_DLQ_ID = "business_dlq.id";

    private LogFields() {
    }
}

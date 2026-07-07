package com.im.api;

/**
 * JSON field names used by IM protocol envelopes and common payloads.
 */
public final class ProtocolFields {

    public static final String OP = "op";
    public static final String SEQ = "seq";
    public static final String CODE = "code";
    public static final String MSG = "msg";
    public static final String DATA = "data";
    public static final String DETAIL = "detail";
    public static final String REQUEST_ID = "requestId";

    public static final String CLIENT_REQUEST_ID = ApiRequest.ATTR_REQUEST_ID;
    public static final String CLIENT_TRACE_ID = ApiRequest.ATTR_TRACE_ID;

    public static final String OP_MESSAGE = "message";
    public static final String OP_FRIEND_APPLY = "friend.apply";
    public static final String OP_GROUP_APPLY = "group.apply";
    public static final String OP_SYSTEM_MESSAGE = "system.message";
    public static final String OP_ERROR = "error";
    public static final String OP_KICKED = "kicked";
    public static final String OP_MESSAGE_REVOKED = "msg_revoke";
    public static final String ACK_SUFFIX = "_ack";

    public static final String REASON = "reason";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String GROUP_ID = "groupId";
    public static final String MESSAGE_ID = "messageId";
    public static final String SEQUENCE_ID = "sequenceId";
    public static final String TIMESTAMP = "timestamp";
    public static final String FROM_USER_ID = "fromUserId";
    public static final String TO_USER_ID = "toUserId";
    public static final String CONTENT_TYPE = "contentType";
    public static final String CONTENT = "content";
    public static final String MESSAGE_SEQ = "messageSeq";
    public static final String STATUS = "status";
    public static final String REVOKER_ID = "revokerId";

    private ProtocolFields() {
    }
}

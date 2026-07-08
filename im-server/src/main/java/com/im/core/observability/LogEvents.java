package com.im.core.observability;

public final class LogEvents {

    public static final String REQUEST_COMPLETED = "im.request.completed";
    public static final String REQUEST_FAILED = "im.request.failed";
    public static final String REQUEST_REJECTED = "im.request.rejected";
    public static final String DISPATCH_REJECTED = "im.request.dispatch.rejected";
    public static final String DISPATCH_FAILED = "im.request.dispatch.failed";
    public static final String HANDLER_MISSING = "im.api.handler.missing";
    public static final String HANDLER_REJECTED = "im.api.handler.rejected";
    public static final String HANDLER_FAILED = "im.api.handler.failed";
    public static final String EXCEPTION_HANDLER_FAILED = "im.api.exception_handler.failed";
    public static final String INTERCEPTOR_REJECTED = "im.api.interceptor.rejected";
    public static final String INTERCEPTOR_FAILED = "im.api.interceptor.failed";
    public static final String AFTER_COMPLETION_FAILED = "im.api.after_completion.failed";
    public static final String AUTH_FAILED = "im.auth.failed";
    public static final String AUTH_SUCCEEDED = "im.auth.succeeded";
    public static final String RATE_LIMIT_REJECTED = "im.rate_limit.rejected";
    public static final String RATE_LIMIT_BACKEND_FAILED = "im.rate_limit.backend_failed";
    public static final String MESSAGE_SEND_ACCEPTED = "im.message.send.accepted";
    public static final String MESSAGE_SEND_PUBLISH_FAILED = "im.message.send.publish_failed";
    public static final String MQ_PUBLISH_SUCCEEDED = "im.mq.publish.succeeded";
    public static final String MQ_PUBLISH_FAILED = "im.mq.publish.failed";
    public static final String MQ_CONSUME_STARTED = "im.mq.consume.started";
    public static final String MQ_CONSUME_SUCCEEDED = "im.mq.consume.succeeded";
    public static final String MQ_CONSUME_FAILED = "im.mq.consume.failed";
    public static final String MQ_MESSAGE_ACKED = "im.mq.message.acked";
    public static final String MESSAGE_PERSIST_SUCCEEDED = "im.message.persist.succeeded";
    public static final String MESSAGE_PERSIST_DUPLICATE = "im.message.persist.duplicate";
    public static final String MESSAGE_PERSIST_FAILED = "im.message.persist.failed";
    public static final String MESSAGE_DELIVERY_ROUTE_RESOLVED = "im.message.delivery.route_resolved";
    public static final String MESSAGE_DELIVERY_OFFLINE_SKIPPED = "im.message.delivery.offline_skipped";
    public static final String MESSAGE_DELIVERY_FAILED = "im.message.delivery.failed";
    public static final String MESSAGE_PUSH_LOCAL_SUCCEEDED = "im.message.push.local_succeeded";
    public static final String MESSAGE_FORWARD_REMOTE_SUCCEEDED = "im.message.forward.remote_succeeded";
    public static final String MESSAGE_FORWARD_REMOTE_FAILED = "im.message.forward.remote_failed";
    public static final String CLUSTER_MESSAGE_PUBLISH_SUCCEEDED = "im.cluster.message.publish.succeeded";
    public static final String CLUSTER_MESSAGE_NO_SUBSCRIBER = "im.cluster.message.no_subscriber";
    public static final String CLUSTER_MESSAGE_RECEIVED = "im.cluster.message.received";
    public static final String CLUSTER_HANDLER_FAILED = "im.cluster.handler.failed";
    public static final String CLUSTER_DELIVERY_RECEIVED = "im.cluster.delivery.received";
    public static final String CLUSTER_DELIVERY_NO_LOCAL_SESSION = "im.cluster.delivery.no_local_session";
    public static final String CLUSTER_DELIVERY_LOCAL_SUCCEEDED = "im.cluster.delivery.local_succeeded";
    public static final String CLUSTER_STALE_ROUTE_REMOVED = "im.cluster.stale_route.removed";
    public static final String CLUSTER_SESSION_COMMAND_APPLIED = "im.cluster.session_command.applied";
    public static final String SESSION_BOUND = "im.session.bound";
    public static final String SESSION_CLEANED = "im.session.cleaned";
    public static final String CONNECTION_OPENED = "im.ws.connection.opened";
    public static final String CONNECTION_CLOSED = "im.ws.connection.closed";
    public static final String CONNECTION_EXCEPTION = "im.ws.connection.exception";
    public static final String LOGIN_SUCCEEDED = "im.login.succeeded";
    public static final String LOGIN_REJECTED = "im.login.rejected";
    public static final String REMOTE_KICK_SENT = "im.login.remote_kick.sent";
    public static final String FRIEND_APPLY_CREATED = "im.friend.apply.created";
    public static final String FRIEND_APPLY_HANDLED = "im.friend.apply.handled";
    public static final String FRIEND_REMOVED = "im.friend.removed";
    public static final String GROUP_CREATED = "im.group.created";
    public static final String GROUP_JOIN_REQUESTED = "im.group.join.requested";
    public static final String GROUP_APPLY_HANDLED = "im.group.apply.handled";
    public static final String GROUP_INFO_UPDATED = "im.group.info.updated";
    public static final String GROUP_DISBANDED = "im.group.disbanded";
    public static final String GROUP_MEMBER_REMOVED = "im.group.member.removed";
    public static final String MESSAGE_REVOKED = "im.message.revoked";
    public static final String SYSTEM_MESSAGE_PUBLISHED = "im.system_message.published";
    public static final String FILE_UPLOAD_SIGNED = "im.file.upload.signed";
    public static final String FILE_UPLOAD_COMPLETED = "im.file.upload.completed";
    public static final String FILE_MULTIPART_PART_UPLOADED = "im.file.multipart.part_uploaded";
    public static final String FILE_MULTIPART_ABORTED = "im.file.multipart.aborted";

    private LogEvents() {
    }
}

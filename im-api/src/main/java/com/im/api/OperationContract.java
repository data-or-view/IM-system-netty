package com.im.api;

import java.util.Arrays;
import java.util.List;

/**
 * Machine-readable protocol contract for every public operation.
 *
 * <p>This is intentionally exhaustive. Adding an {@link Operation} must update
 * this switch so CI catches protocol drift before SDK/web/backend behavior splits.</p>
 */
public record OperationContract(
        Operation operation,
        TransportType transport,
        String category,
        String requestShape,
        String responseShape
) {

    public static OperationContract forOperation(Operation operation) {
        return switch (operation) {
            case USER_REGISTER -> http(operation, "user", "nickname?, password?, faceUrl?", "userId, nickname, faceUrl");
            case USER_ME -> http(operation, "user", "Authorization", "current user profile");
            case USER_INFO -> http(operation, "user", "userId", "user profile");
            case USER_SEARCH -> http(operation, "user", "keyword, limit?", "users[]");
            case USER_UPDATE -> http(operation, "user", "nickname?, faceUrl?, ex?", "updated user profile");

            case FRIEND_APPLY -> http(operation, "friend", "toUserId, reqMsg?", "apply status");
            case FRIEND_APPROVE -> http(operation, "friend", "applyId/fromUserId, agreed, handleMsg?", "approval status");
            case FRIEND_REMOVE -> http(operation, "friend", "friendUserId", "remove status");
            case FRIEND_LIST -> http(operation, "friend", "none", "friends[]");
            case FRIEND_BLACK -> http(operation, "friend", "userId", "blacklist status");
            case FRIEND_UNBLACK -> http(operation, "friend", "userId", "blacklist status");
            case FRIEND_BLACKLIST -> http(operation, "friend", "none", "blacklist[]");
            case FRIEND_APPLY_RECEIVED -> http(operation, "friend", "onlyPending?", "received applies[]");
            case FRIEND_APPLY_SENT -> http(operation, "friend", "onlyPending?", "sent applies[]");
            case FRIEND_APPLY_DETAIL -> http(operation, "friend", "applyId", "apply detail");
            case FRIEND_APPLY_UNHANDLED_COUNT -> http(operation, "friend", "none", "count");

            case GROUP_CREATE -> http(operation, "group", "groupName, members?, groupType?, needVerification?", "groupId");
            case GROUP_JOIN -> http(operation, "group", "groupId, reqMsg?", "join result");
            case GROUP_QUIT -> http(operation, "group", "groupId", "quit status");
            case GROUP_KICK -> http(operation, "group", "groupId, targetUserId", "kick status");
            case GROUP_DISBAND -> http(operation, "group", "groupId", "disband result");
            case GROUP_INFO_UPDATE -> http(operation, "group", "groupId, profile fields", "update status");
            case GROUP_OWNER_TRANSFER -> http(operation, "group", "groupId, newOwnerId", "transfer status");
            case GROUP_MEMBER_ROLE_SET -> http(operation, "group", "groupId, targetUserId, roleLevel", "role status");
            case GROUP_MEMBER_INFO_UPDATE -> http(operation, "group", "groupId, member fields", "update status");
            case GROUP_INFO -> http(operation, "group", "groupId", "group information");
            case GROUP_LIST -> http(operation, "group", "none", "groups[]");
            case GROUP_SEARCH -> http(operation, "group", "keyword, limit?", "groups[]");
            case GROUP_MEMBERS -> http(operation, "group", "groupId", "members[]");
            case GROUP_MUTE_ALL -> http(operation, "group", "groupId, muted", "mute status");
            case GROUP_APPLY_LIST -> http(operation, "group", "groupId?, onlyPending?", "group applies[]");
            case GROUP_APPLY_UNHANDLED_COUNT -> http(operation, "group", "none", "count");
            case GROUP_APPLY_APPROVE -> http(operation, "group", "groupId, userId, agreed, handleMsg?", "handle result");
            case GROUP_CALL_START -> http(operation, "group-call", "groupId, callType", "room/token summary");
            case GROUP_CALL_JOIN -> http(operation, "group-call", "groupId", "room/token summary");
            case GROUP_CALL_LEAVE -> http(operation, "group-call", "groupId", "leave status");
            case GROUP_CALL_END -> http(operation, "group-call", "groupId", "end status");
            case GROUP_CALL_ACTIVE -> http(operation, "group-call", "groupId", "active call summary");

            case CONVERSATION_LIST -> http(operation, "conversation", "none", "conversations[]");
            case CONVERSATION_SET -> http(operation, "conversation", "conversationId, settings", "settings status");
            case CONVERSATION_READ -> http(operation, "conversation", "conversationId, readSeq", "unread count");

            case CHAT_PULL -> http(operation, "message", "conversationId, startSeq?, endSeq?, limit?", "messages[]");
            case CHAT_SEQ -> http(operation, "message", "conversationId", "maxSeq");
            case CHAT_SYNC -> http(operation, "message", "seqs{conversationId:lastSeq}", "messages by conversation");
            case CHAT_SEARCH -> http(operation, "message", "keyword, conversationIds?, limit?, offset?", "messages[], totalCount");
            case CHAT_SEND -> ws(operation, "message", "clientMsgId, toUserId, content", "chat.send_ack");
            case CHAT_SEND_GROUP -> ws(operation, "message", "clientMsgId, groupId, content", "chat.send_ack");
            case CHAT_REVOKE -> http(operation, "message", "conversationId, messageSeq, groupId?", "revoke status");

            case FILE_UPLOAD -> http(operation, "file", "binary body + file metadata query", "file metadata");
            case FILE_UPLOAD_SIGN -> http(operation, "file", "fileName, mimeType, size", "upload signature");
            case FILE_UPLOAD_COMPLETE -> http(operation, "file", "fileId, objectKey", "file metadata");
            case FILE_DOWNLOAD_SIGN -> http(operation, "file", "fileId", "download signature");
            case FILE_MULTIPART_INIT -> http(operation, "file", "fileName, mimeType, size", "multipart upload session");
            case FILE_MULTIPART_PART_SIGN -> http(operation, "file", "uploadId, partNumber", "part upload signature");
            case FILE_MULTIPART_UPLOAD -> http(operation, "file", "binary part body + query metadata", "part upload status");
            case FILE_MULTIPART_COMPLETE -> http(operation, "file", "uploadId, parts[]", "file metadata");
            case FILE_MULTIPART_ABORT -> http(operation, "file", "uploadId", "abort status");

            case SYSTEM_CHANNEL_LIST -> http(operation, "system", "none", "channels[]");
            case SYSTEM_MESSAGE_LIST -> http(operation, "system", "channelId?, onlyUnread?, limit?", "messages[]");
            case SYSTEM_MESSAGE_DETAIL -> http(operation, "system", "messageId", "message detail");
            case SYSTEM_MESSAGE_READ -> http(operation, "system", "messageId", "read status");
            case SYSTEM_MESSAGE_READ_ALL -> http(operation, "system", "channelId?", "read status");
            case SYSTEM_MESSAGE_UNREAD_COUNT -> http(operation, "system", "none", "count");
            case ADMIN_SYSTEM_MESSAGE_PUBLISH -> http(operation, "admin-system", "channelId, title, content, receiverIds?", "messageId");

            case LOGIN -> ws(operation, "session", "userId, password?, platform", "tokens + profile");
            case REGISTER -> ws(operation, "session", "nickname?, password?", "tokens + profile");
            case HEARTBEAT -> ws(operation, "session", "token?, refreshToken?", "heartbeat ack + optional token refresh");
        };
    }

    public static List<OperationContract> all() {
        return Arrays.stream(Operation.values()).map(OperationContract::forOperation).toList();
    }

    private static OperationContract http(Operation operation, String category, String requestShape, String responseShape) {
        return new OperationContract(operation, TransportType.HTTP_ONLY, category, requestShape, responseShape);
    }

    private static OperationContract ws(Operation operation, String category, String requestShape, String responseShape) {
        return new OperationContract(operation, TransportType.WS_ONLY, category, requestShape, responseShape);
    }
}

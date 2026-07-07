package com.im.api;

import java.util.Arrays;
import java.util.List;

/**
 * Authorization ownership matrix for public protocol operations.
 *
 * <p>The enum switch is exhaustive by design: every new operation must declare
 * its resource scope and the component that enforces it.</p>
 */
public record AuthzPolicy(
        Operation operation,
        Scope scope,
        String rule,
        String enforcedBy
) {

    public enum Scope {
        PUBLIC,
        SELF,
        USER_LOOKUP,
        FRIEND_RELATION,
        FRIEND_REQUEST,
        GROUP_MEMBER,
        GROUP_MANAGER,
        GROUP_OWNER,
        CONVERSATION_MEMBER,
        FILE_OWNER,
        SYSTEM_INBOX,
        ADMIN,
        WS_SESSION
    }

    public static AuthzPolicy forOperation(Operation operation) {
        return switch (operation) {
            case USER_REGISTER -> policy(operation, Scope.PUBLIC, "Anyone may create a development user.", "UserHandler/RegisterHandler");
            case LOGIN, REGISTER -> policy(operation, Scope.PUBLIC, "Anyone may start an authenticated session with valid credentials.", "LoginHandler/RegisterHandler");
            case HEARTBEAT -> policy(operation, Scope.WS_SESSION, "Open WS sessions may renew binding with valid token material.", "HeartbeatHandler");

            case USER_ME, USER_UPDATE -> policy(operation, Scope.SELF, "Caller may read or update only their own profile.", "AuthInterceptor + UserHandler");
            case USER_INFO, USER_SEARCH -> policy(operation, Scope.USER_LOOKUP, "Authenticated callers may search public profile fields only.", "AuthInterceptor + UserHandler");

            case FRIEND_APPLY -> policy(operation, Scope.FRIEND_REQUEST, "Caller creates an application from self to target user.", "FriendApplyPolicy + DbFriendManager");
            case FRIEND_APPROVE -> policy(operation, Scope.FRIEND_REQUEST, "Only the receiver of a pending application may handle it.", "DbFriendManager");
            case FRIEND_REMOVE, FRIEND_BLACK, FRIEND_UNBLACK -> policy(operation, Scope.FRIEND_RELATION, "Caller may mutate only their own friend/blacklist relation.", "FriendHandler + DbFriendManager");
            case FRIEND_LIST, FRIEND_BLACKLIST, FRIEND_APPLY_RECEIVED, FRIEND_APPLY_SENT,
                    FRIEND_APPLY_DETAIL, FRIEND_APPLY_UNHANDLED_COUNT ->
                    policy(operation, Scope.SELF, "Caller may list only their own friend data.", "FriendHandler + DbFriendManager");

            case GROUP_CREATE -> policy(operation, Scope.SELF, "Caller becomes owner of the created group.", "GroupHandler + DbGroupManager");
            case GROUP_JOIN -> policy(operation, Scope.GROUP_MEMBER, "Caller may join or apply according to target group policy.", "GroupApplyPolicy + DbGroupManager");
            case GROUP_QUIT -> policy(operation, Scope.GROUP_MEMBER, "Caller may quit only a group they belong to.", "DbGroupManager");
            case GROUP_KICK, GROUP_INFO_UPDATE, GROUP_MEMBER_ROLE_SET, GROUP_MEMBER_INFO_UPDATE,
                    GROUP_MUTE_ALL, GROUP_APPLY_LIST, GROUP_APPLY_UNHANDLED_COUNT, GROUP_APPLY_APPROVE ->
                    policy(operation, Scope.GROUP_MANAGER, "Group owner/admin permission is required.", "GroupHandler + DbGroupManager");
            case GROUP_DISBAND, GROUP_OWNER_TRANSFER ->
                    policy(operation, Scope.GROUP_OWNER, "Only current group owner may perform this operation.", "GroupHandler + DbGroupManager");
            case GROUP_INFO, GROUP_MEMBERS ->
                    policy(operation, Scope.GROUP_MEMBER, "Caller must be a current member for private group data.", "GroupHandler + DbGroupManager");
            case GROUP_LIST, GROUP_SEARCH ->
                    policy(operation, Scope.SELF, "Caller sees joined groups or public searchable groups.", "GroupHandler + DbGroupManager");
            case GROUP_CALL_START, GROUP_CALL_JOIN, GROUP_CALL_LEAVE, GROUP_CALL_END, GROUP_CALL_ACTIVE ->
                    policy(operation, Scope.GROUP_MEMBER, "Caller must be a current group member.", "GroupCallHandler + GroupCallManager");

            case CONVERSATION_LIST -> policy(operation, Scope.SELF, "Caller lists only their own conversation view.", "ConversationHandler");
            case CONVERSATION_SET, CONVERSATION_READ ->
                    policy(operation, Scope.CONVERSATION_MEMBER, "Caller must be able to read the conversation.", "ConversationAccessChecker");

            case CHAT_PULL, CHAT_SEQ, CHAT_SYNC, CHAT_SEARCH, CHAT_REVOKE ->
                    policy(operation, Scope.CONVERSATION_MEMBER, "Caller must be able to read the conversation; revoke also checks sender/group role.", "ConversationAccessChecker + RevokeUseCase/IMessageStore");
            case CHAT_SEND ->
                    policy(operation, Scope.FRIEND_RELATION, "Caller may send single chat only when send policy allows the target.", "DefaultChatSendPolicy + SendMessageUseCase");
            case CHAT_SEND_GROUP ->
                    policy(operation, Scope.GROUP_MEMBER, "Caller may send group chat only as a current member.", "DefaultChatSendPolicy + SendMessageUseCase");

            case FILE_UPLOAD, FILE_UPLOAD_SIGN, FILE_UPLOAD_COMPLETE, FILE_DOWNLOAD_SIGN,
                    FILE_MULTIPART_INIT, FILE_MULTIPART_PART_SIGN, FILE_MULTIPART_UPLOAD,
                    FILE_MULTIPART_COMPLETE, FILE_MULTIPART_ABORT ->
                    policy(operation, Scope.FILE_OWNER, "Caller may operate only on their own upload/download session or accessible message attachment.", "File handlers + DirectFileTransferUseCase");

            case SYSTEM_CHANNEL_LIST, SYSTEM_MESSAGE_LIST, SYSTEM_MESSAGE_DETAIL, SYSTEM_MESSAGE_READ,
                    SYSTEM_MESSAGE_READ_ALL, SYSTEM_MESSAGE_UNREAD_COUNT ->
                    policy(operation, Scope.SYSTEM_INBOX, "Caller may read and mark only their own system inbox.", "SystemMessageHandler");
            case ADMIN_SYSTEM_MESSAGE_PUBLISH ->
                    policy(operation, Scope.ADMIN, "Only admin-level users may publish system messages.", "SystemMessageHandler");
        };
    }

    public static List<AuthzPolicy> all() {
        return Arrays.stream(Operation.values()).map(AuthzPolicy::forOperation).toList();
    }

    private static AuthzPolicy policy(Operation operation, Scope scope, String rule, String enforcedBy) {
        return new AuthzPolicy(operation, scope, rule, enforcedBy);
    }
}

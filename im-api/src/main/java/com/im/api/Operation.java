package com.im.api;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 统一业务操作枚举（单点真理）。
 *
 * <p>每个枚举值携带操作名、HTTP 路由（WS 独有操作为 null）、是否需要认证。
 * 替代 {@code OperationMapping} 和 {@code AuthInterceptor.WHITE_LIST}。</p>
 *
 * <p>WS 帧通过 {@link #fromOpName(String)} 查找，
 * HTTP 请求通过 {@link #fromHttp(String, String)} 查找。</p>
 */
public enum Operation {

    // ── 用户 ──
    USER_REGISTER("user.register", "POST", "/api/user/register", false),
    USER_INFO("user.info", "GET", "/api/user/info", true),
    USER_SEARCH("user.search", "GET", "/api/user/search", true),
    USER_UPDATE("user.update", "POST", "/api/user/update", true),

    // ── 好友 ──
    FRIEND_APPLY("friend.apply", "POST", "/api/friend/apply", true),
    FRIEND_APPROVE("friend.approve", "POST", "/api/friend/approve", true),
    FRIEND_REMOVE("friend.remove", "POST", "/api/friend/remove", true),
    FRIEND_LIST("friend.list", "GET", "/api/friend/list", true),
    FRIEND_BLACK("friend.black", "POST", "/api/friend/black", true),
    FRIEND_UNBLACK("friend.unblack", "POST", "/api/friend/unblack", true),
    FRIEND_BLACKLIST("friend.blacklist", "GET", "/api/friend/blacklist", true),
    FRIEND_APPLY_SENT("friend.get_sent_apply_list", "GET", "/api/friend/apply/sent", true),
    FRIEND_APPLY_DETAIL("friend.get_apply_detail", "GET", "/api/friend/apply/detail", true),
    FRIEND_APPLY_UNHANDLED_COUNT("friend.get_unhandled_apply_count", "GET", "/api/friend/apply/unhandled/count", true),

    // ── 群组 ──
    GROUP_CREATE("group.create", "POST", "/api/group/create", true),
    GROUP_JOIN("group.join", "POST", "/api/group/join", true),
    GROUP_QUIT("group.quit", "POST", "/api/group/quit", true),
    GROUP_KICK("group.kick", "POST", "/api/group/kick", true),
    GROUP_DISBAND("group.disband", "POST", "/api/group/disband", true),
    GROUP_INFO_UPDATE("group.info.update", "POST", "/api/group/info/update", true),
    GROUP_INFO("group.info", "GET", "/api/group/info", true),
    GROUP_LIST("group.list", "GET", "/api/group/list", true),
    GROUP_SEARCH("group.search", "GET", "/api/group/search", true),
    GROUP_MEMBERS("group.members", "GET", "/api/group/members", true),
    GROUP_MUTE_ALL("group.mute_all", "POST", "/api/group/mute/all", true),

    // ── 会话 ──
    CONVERSATION_LIST("conversation.list", "GET", "/api/conversation/list", true),
    CONVERSATION_SET("conversation.set", "POST", "/api/conversation/set", true),
    CONVERSATION_READ("conversation.read", "POST", "/api/conversation/read", true),

    // ── 消息 ──
    CHAT_PULL("chat.pull", "POST", "/api/msg/pull", true),
    CHAT_SEQ("chat.seq", "GET", "/api/msg/seq", true),
    CHAT_SYNC("chat.sync", "POST", "/api/msg/sync", true),
    CHAT_SEARCH("chat.search", "POST", "/api/msg/search", true),
    CHAT_SEND("chat.send", null, null, true),
    CHAT_SEND_GROUP("chat.send.group", null, null, true),
    CHAT_REVOKE("msg_revoke", "POST", "/api/msg/revoke", true),

    // ── 文件 ──
    FILE_UPLOAD("file.upload", "POST", "/api/file/upload", true),
    FILE_MULTIPART_INIT("file.multipart.init", "POST", "/api/file/multipart/init", true),
    FILE_MULTIPART_UPLOAD("file.multipart.upload", "POST", "/api/file/multipart/upload", true),
    FILE_MULTIPART_COMPLETE("file.multipart.complete", "POST", "/api/file/multipart/complete", true),
    FILE_MULTIPART_ABORT("file.multipart.abort", "POST", "/api/file/multipart/abort", true),

    // ── WS 独有（无 HTTP 映射） ──
    LOGIN("login", null, null, false),
    REGISTER("register", null, null, false),
    HEARTBEAT("heartbeat", null, null, false);

    private final String opName;
    private final String httpMethod;     // "GET" / "POST" / null
    private final String httpPath;       // "/api/user/search" / null
    private final boolean requireAuth;

    Operation(String opName, String httpMethod, String httpPath, boolean requireAuth) {
        this.opName = opName;
        this.httpMethod = httpMethod;
        this.httpPath = httpPath;
        this.requireAuth = requireAuth;
    }

    // ── lookup caches ──

    private static final Map<String, Operation> BY_NAME = new HashMap<>();
    private static final Map<String, Operation> BY_HTTP = new HashMap<>();

    static {
        for (Operation op : values()) {
            BY_NAME.put(op.opName, op);
            if (op.httpMethod != null && op.httpPath != null) {
                BY_HTTP.put(op.httpMethod + ":" + op.httpPath, op);
            }
        }
    }

    // ── getters ──

    public String opName() { return opName; }
    public String httpMethod() { return httpMethod; }
    public String httpPath() { return httpPath; }
    public boolean requireAuth() { return requireAuth; }

    /** 该 operation 是否有对应的 HTTP 路由 */
    public boolean hasHttpMapping() { return httpMethod != null && httpPath != null; }

    // ── lookup ──

    /**
     * 根据 WS 帧的 "op" 字段查找 Operation。
     *
     * @param opName "user.search" 等操作名字符串
     * @return 匹配的 Operation，未知操作返回 {@code null}
     */
    public static Operation fromOpName(String opName) {
        return opName != null ? BY_NAME.get(opName) : null;
    }

    /**
     * 根据 HTTP Method + Path 查找 Operation。
     *
     * @param method "GET" / "POST"
     * @param path   "/api/user/search"
     * @return 匹配的 Operation，未匹配返回 {@code null}
     */
    public static Operation fromHttp(String method, String path) {
        if (method == null || path == null) return null;
        return BY_HTTP.get(method + ":" + path);
    }
}

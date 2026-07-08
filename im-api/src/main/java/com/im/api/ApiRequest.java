package com.im.api;

import java.util.HashMap;
import java.util.Map;

/**
 * 协议无关的统一请求对象。
 *
 * <p>WS 和 HTTP 的请求在 Adapter 层统一转为 {@code ApiRequest}，
 * 后续的拦截器链和业务 handler 不再感知协议差异。</p>
 *
 * <p>职责区分：</p>
 * <ul>
 *   <li>{@code params} — 客户端传来的业务参数（body JSON + query string 合并）</li>
 *   <li>{@code headers} — 透传协议头部（Authorization、Content-Type、traceId 等）</li>
 *   <li>{@code attributes} — 拦截器/框架填充的上下文（userId、role 等），不对客户端暴露</li>
 *   <li>{@code bodyRaw} — 原始二进制载荷，仅文件上传场景使用</li>
 * </ul>
 */
public class ApiRequest {

    public static final String ATTR_USER_ID = "_uid";
    public static final String ATTR_CONNECTION_ID = "_connectionId";
    public static final String ATTR_CLIENT_IP = "_clientIp";
    public static final String ATTR_REQUEST_ID = "_requestId";
    public static final String ATTR_TRACE_ID = "_traceId";
    public static final String ATTR_WS_SEQ = "_wsSeq";
    public static final String ATTR_PROTOCOL = "_protocol";
    public static final String ATTR_NODE_ID = "_nodeId";
    public static final String ATTR_HTTP_METHOD = "_httpMethod";
    public static final String ATTR_HTTP_PATH = "_httpPath";

    private final String operation;
    private final Operation op;
    private final Map<String, Object> params;
    private final Map<String, String> headers;
    private final Map<String, Object> attributes;
    private final ResponseWriter responseWriter;
    private final byte[] bodyRaw;

    /** 主构造：通过 Operation 枚举创建（Adapter 层使用） */
    public ApiRequest(Operation operation, Map<String, Object> params,
                      Map<String, String> headers, ResponseWriter responseWriter,
                      byte[] bodyRaw) {
        this.op = operation;
        this.operation = operation.opName();
        this.params = params != null ? params : Map.of();
        this.headers = headers != null ? headers : Map.of();
        this.attributes = new HashMap<>();
        this.responseWriter = responseWriter;
        this.bodyRaw = bodyRaw;
    }

    /** 业务操作名，如 "user.search"、"friend.apply"、"chat.send" */
    public String operation() { return operation; }

    /** 解析后的 Operation 枚举（含路由、认证等元数据） */
    public Operation op() { return op; }

    /** 业务参数（body JSON + query string 合并结果） */
    public Map<String, Object> params() { return params; }

    /** 便捷取参数 */
    @SuppressWarnings("unchecked")
    public <T> T param(String key) { return (T) params.get(key); }
    public String getString(String key) { Object v = params.get(key); return v != null ? v.toString() : null; }
    public String getString(String key, String def) { Object v = params.get(key); return v != null ? v.toString() : def; }
    public int getInt(String key, int def) {
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (NumberFormatException e) { return def; }
        return def;
    }
    public long getLong(String key, long def) {
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return def; }
        return def;
    }
    public boolean getBoolean(String key, boolean def) {
        Object v = params.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v);
        return def;
    }

    /** 透传协议头部 */
    public Map<String, String> headers() { return headers; }
    public String header(String key) { return headers.get(key); }

    /** 拦截器/框架上下文（userId、role 等） */
    public Map<String, Object> attributes() { return attributes; }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T attribute(String key) { return (T) attributes.get(key); }

    /**
     * 获取当前认证用户 ID。
     * 由 AuthInterceptor 在验证 token 后设置到 {@code _uid} attribute。
     * 未认证的操作返回 null。
     */
    public String currentUserId() {
        Object uid = attributes.get(ATTR_USER_ID);
        return uid != null ? uid.toString() : null;
    }

    /** 协议响应写回策略 */
    public ResponseWriter responseWriter() { return responseWriter; }

    /** 原始二进制载荷（仅文件上传场景） */
    public byte[] bodyRaw() { return bodyRaw; }
}

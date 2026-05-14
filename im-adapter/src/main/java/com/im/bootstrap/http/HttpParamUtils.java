package com.im.bootstrap.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP REST 参数解析工具方法。
 *
 * <p>从请求中提取 JSON body 或 query string 参数，
 * 提供类型安全的取值方法（str / bool / int / long）。</p>
 */
public final class HttpParamUtils {

    private static final ObjectMapper MAPPER = JsonResponse.mapper();

    private HttpParamUtils() {}

    // ── 请求解析 ──

    /**
     * 解析 JSON body 为 Map。
     */
    public static Map<String, Object> parseJsonBody(FullHttpRequest req) {
        if (req.content().readableBytes() == 0) return Map.of();
        try {
            ByteBuf buf = req.content();
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return MAPPER.readValue(bytes, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new com.im.api.ImException(com.im.api.ImErrorCode.BAD_REQUEST,
                    "invalid JSON body: " + e.getMessage());
        }
    }

    /**
     * 解析 query string 为 Map。
     */
    public static Map<String, String> parseQuery(FullHttpRequest req) {
        String uri = req.uri();
        int qIdx = uri.indexOf('?');
        if (qIdx < 0) return Map.of();
        Map<String, String> params = new HashMap<>();
        String query = uri.substring(qIdx + 1);
        for (String pair : query.split("&")) {
            int eIdx = pair.indexOf('=');
            if (eIdx > 0) {
                params.put(decodeURI(pair.substring(0, eIdx)), decodeURI(pair.substring(eIdx + 1)));
            }
        }
        return params;
    }

    private static String decodeURI(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // ── Map 取值工具 ──

    public static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    public static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v != null ? v.toString() : def;
    }

    public static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v);
        return def;
    }

    public static int intObj(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    public static long longObj(Map<String, Object> map, String key, long def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    public static int intParam(Map<String, String> params, String key, int def) {
        String v = params.get(key);
        if (v != null) {
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }
}

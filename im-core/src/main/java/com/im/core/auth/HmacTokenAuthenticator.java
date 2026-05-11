package com.im.core.auth;

import com.im.api.IAuthenticator;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * HMAC-SHA256 自签 token 认证器。
 *
 * Token 格式（兼容 JWT 三段式）：
 *   base64url(header) + "." + base64url(payload) + "." + base64url(signature)
 *
 * header:   {"alg":"HS256","typ":"JWT"}
 * payload:  {"uid":"user123","exp":1700000000,"iat":1699999999}
 * signature: HMAC-SHA256(base64url(header) + "." + base64url(payload), secret)
 *
 * 生产环境建议替换为标准 JJWT 库（支持 RSA/ECDSA、jwks 轮换）。
 */
public class HmacTokenAuthenticator implements IAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(HmacTokenAuthenticator.class);

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String HEADER_B64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;

    public HmacTokenAuthenticator(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String issueToken(String userId, Duration ttl) {
        long now = Instant.now().getEpochSecond();
        long exp = now + ttl.toSeconds();
        String payload = "{\"uid\":\"" + escapeJson(userId) + "\",\"exp\":" + exp + ",\"iat\":" + now + "}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        String signingInput = HEADER_B64 + "." + payloadB64;
        String signature = sign(signingInput);

        String token = signingInput + "." + signature;
        log.debug("Issued token for user={}, exp={}", userId, exp);
        return token;
    }

    @Override
    public String authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "token is required");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "invalid token format");
        }

        // 校验签名
        String signingInput = parts[0] + "." + parts[1];
        String expectedSig = sign(signingInput);
        if (!constantTimeEquals(parts[2], expectedSig)) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "invalid token signature");
        }

        // 解析 payload
        String payloadJson;
        try {
            payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "invalid token payload encoding");
        }

        // 提取 uid 和 exp
        String userId = extractJsonField(payloadJson, "uid");
        long exp = Long.parseLong(extractJsonField(payloadJson, "exp"));

        // 检查过期
        if (Instant.now().getEpochSecond() > exp) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "token expired");
        }

        return userId;
    }

    // ========== private ==========

    private String sign(String input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            byte[] sigBytes = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }

    /** 恒定时间比较，防止时序攻击 */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /** 极简 JSON 字段提取（无 Jackson 依赖） */
    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            // 尝试数字类型（无引号）
            key = "\"" + field + "\":";
            start = json.indexOf(key);
            if (start < 0) {
                throw new ImException(ImErrorCode.UNAUTHORIZED, "token missing field: " + field);
            }
            start += key.length();
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            if (end < 0) throw new ImException(ImErrorCode.UNAUTHORIZED, "malformed token payload");
            return json.substring(start, end);
        }
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "malformed token payload");
        }
        return json.substring(start, end);
    }

    /** JSON 字符串转义 */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

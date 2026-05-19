package com.im.core.auth;

import com.im.api.IAuthenticator;
import com.im.common.exception.ImException;
import com.im.common.enums.ImErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT（JJWT）token 认证实现。
 *
 * <p>使用 HMAC-SHA256 签名，支持双 token 机制：</p>
 * <ul>
 *   <li>access token（typ 为空）— 短有效期（2 小时）</li>
 *   <li>refresh token（typ="refresh"）— 长有效期（30 天），用于自动续期</li>
 * </ul>
 *
 * <p>替代 {@code HmacTokenAuthenticator}，接口完全兼容，零调用方变更。</p>
 */
public class JwtAuthenticator implements IAuthenticator {

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_LVL = "lvl";
    private static final String CLAIM_TYP = "typ";
    private static final String REFRESH_TYP = "refresh";

    /** refresh token 剩余不足此天数时轮换 */
    private static final Duration ROTATION_THRESHOLD = Duration.ofDays(7);

    private final SecretKey key;
    private final JwtParser parser;

    public JwtAuthenticator(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.parser = Jwts.parser().verifyWith(key).build();
    }

    @Override
    public String issueToken(String userId, Duration ttl) {
        return issueToken(userId, ttl, 0);
    }

    @Override
    public String issueToken(String userId, Duration ttl, int appManagerLevel) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(CLAIM_UID, userId)
                .claim(CLAIM_LVL, appManagerLevel)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    @Override
    public String issueRefreshToken(String userId, Duration ttl, int appManagerLevel) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(CLAIM_UID, userId)
                .claim(CLAIM_LVL, appManagerLevel)
                .claim(CLAIM_TYP, REFRESH_TYP)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    @Override
    public TokenRefreshResult refreshAccessToken(String refreshToken) {
        // 验证 refresh token
        Claims claims = parseToken(refreshToken);
        if (claims == null) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "invalid refresh token");
        }

        // 检查 typ 标记，必须是 refresh token
        if (!REFRESH_TYP.equals(claims.get(CLAIM_TYP, String.class))) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "not a refresh token");
        }

        String userId = claims.get(CLAIM_UID, String.class);
        int appManagerLevel = claims.get(CLAIM_LVL, Integer.class);

        // 签发新的 access token（2 小时）
        String newAccessToken = issueToken(userId, Duration.ofHours(2), appManagerLevel);

        // 检查 refresh token 是否需要轮换
        Date exp = claims.getExpiration();
        String newRefreshToken = null;
        if (exp != null && Instant.now().plus(ROTATION_THRESHOLD).isAfter(exp.toInstant())) {
            newRefreshToken = issueRefreshToken(userId, Duration.ofDays(30), appManagerLevel);
        }

        return new TokenRefreshResult(newAccessToken, newRefreshToken);
    }

    @Override
    public String authenticate(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "invalid token");
        }
        String userId = claims.get(CLAIM_UID, String.class);
        if (userId == null) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "token missing userId");
        }
        return userId;
    }

    @Override
    public int getAppManagerLevel(String token) {
        Claims claims = parseToken(token);
        if (claims == null) return 0;
        Integer lvl = claims.get(CLAIM_LVL, Integer.class);
        return lvl != null ? lvl : 0;
    }

    private Claims parseToken(String token) {
        try {
            return parser.parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}

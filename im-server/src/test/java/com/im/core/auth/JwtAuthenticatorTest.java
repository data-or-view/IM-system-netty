package com.im.core.auth;

import com.im.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtAuthenticatorTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void authenticateInvalidTokenThrowsUnauthorized() {
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);

        assertThrows(UnauthorizedException.class, () -> authenticator.authenticate("bad-token"));
    }

    @Test
    void refreshRejectsAccessToken() {
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);
        String accessToken = authenticator.issueToken("alice", Duration.ofHours(1));

        assertThrows(UnauthorizedException.class, () -> authenticator.refreshAccessToken(accessToken));
    }

    @Test
    void accessAuthenticationRejectsRefreshToken() {
        JwtAuthenticator authenticator = new JwtAuthenticator(SECRET);
        String refreshToken = authenticator.issueRefreshToken("alice", Duration.ofDays(30), 1);

        assertThrows(UnauthorizedException.class, () -> authenticator.authenticateAccessToken(refreshToken));
        assertEquals(0, authenticator.getAppManagerLevel(refreshToken));
    }
}

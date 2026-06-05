package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IAuthenticator;
import com.im.api.TokenRefreshResult;
import com.im.api.Operation;
import com.im.api.ResponseWriter;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthInterceptorTest {

    @Test
    void missingTokenThrowsUnauthorizedException() {
        AuthInterceptor interceptor = new AuthInterceptor(new NoopAuthenticator());
        ApiRequest request = new ApiRequest(
                Operation.USER_INFO,
                Map.of(),
                Map.of(),
                new NoopResponseWriter(),
                null
        );

        assertThrows(UnauthorizedException.class, () -> interceptor.preHandle(request));
    }

    private static class NoopAuthenticator implements IAuthenticator {
        @Override
        public String issueToken(String userId, Duration ttl) {
            return "token";
        }

        @Override
        public String authenticate(String token) {
            return "user-1";
        }

        @Override
        public String issueRefreshToken(String userId, Duration ttl, int appManagerLevel) {
            return "refresh-token";
        }

        @Override
        public TokenRefreshResult refreshAccessToken(String refreshToken) {
            return new TokenRefreshResult("access-token", null);
        }
    }

    private static class NoopResponseWriter implements ResponseWriter {
        @Override
        public void write(Object result) {
        }

        @Override
        public void writeError(ImErrorCode code, String detail) {
        }
    }
}

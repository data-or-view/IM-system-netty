package com.im.api;

import com.im.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestPreconditionsTest {

    @Test
    void requireUserReturnsCurrentUserId() {
        ApiRequest request = request();
        request.setAttribute(ApiRequest.ATTR_USER_ID, "u1");

        assertEquals("u1", RequestPreconditions.requireUser(request));
    }

    @Test
    void requireUserRejectsMissingUserId() {
        assertThrows(UnauthorizedException.class, () -> RequestPreconditions.requireUser(request()));
    }

    @Test
    void requireUserRejectsBlankUserId() {
        ApiRequest request = request();
        request.setAttribute(ApiRequest.ATTR_USER_ID, "  ");

        assertThrows(UnauthorizedException.class, () -> RequestPreconditions.requireUser(request));
    }

    private static ApiRequest request() {
        return new ApiRequest(Operation.USER_ME, Map.of(), Map.of(), null, null);
    }
}

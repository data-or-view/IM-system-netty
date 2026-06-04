package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.ResponseWriter;
import com.im.common.enums.ImErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TelemetryInterceptorTest {

    @Test
    void completionAttributesIncludeAuthenticatedUserId() {
        ApiRequest request = new ApiRequest(
                Operation.USER_INFO,
                Map.of(),
                Map.of("Content-Type", "application/json"),
                new NoopResponseWriter(),
                null
        );
        request.setAttribute("_uid", "user-1");

        Map<String, Object> attributes = TelemetryInterceptor.completionAttributes(request, null);

        assertEquals("user-1", attributes.get("app.user.id"));
        assertFalse(attributes.containsKey("app.error"));
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

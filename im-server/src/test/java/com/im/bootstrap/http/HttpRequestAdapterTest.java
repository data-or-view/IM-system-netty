package com.im.bootstrap.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.bootstrap.RequestAdmission;
import com.im.bootstrap.RequestScope;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.InfrastructureException;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpRequestAdapterTest {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Test
    void requestIdHeaderIsExposedAsRequestAttribute() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        ExecutorService directExecutor = new DirectExecutorService();
        dispatcher.registerHandler(com.im.api.Operation.USER_INFO, req -> {
            assertEquals("req-http-1", req.attribute("_requestId"));
            return java.util.Map.of("ok", true);
        });
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestAdapter(dispatcher, directExecutor));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/api/user/info"
        );
        request.headers().set("X-Request-Id", "req-http-1");

        assertFalse(channel.writeInbound(request));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        Map<String, Object> body = readBody(response);
        assertEquals(0, body.get("code"));
        assertEquals("ok", body.get("msg"));
        assertEquals("req-http-1", body.get("requestId"));
        assertEquals(Map.of("ok", true), body.get("data"));
    }

    @Test
    void corsPreflightAllowsSdkRequestIdHeader() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        ExecutorService directExecutor = new DirectExecutorService();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestAdapter(dispatcher, directExecutor));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.OPTIONS,
                "/api/user/login"
        );
        request.headers().set(HttpHeaderNames.ORIGIN, "http://127.0.0.1:39073");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-request-id");

        assertFalse(channel.writeInbound(request));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        Map<String, Object> body = readBody(response);
        assertEquals(0, body.get("code"));
        assertEquals("ok", body.get("msg"));
        assertEquals(Map.of(), body.get("data"));
        assertEquals("http://127.0.0.1:39073", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        String allowHeaders = response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS);
        assertNotNull(allowHeaders);
        assertEquals(true, allowHeaders.toLowerCase().contains("x-request-id"));
        assertEquals("X-Request-Id", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

    @Test
    void corsPreflightDoesNotEchoUnknownOrigin() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        ExecutorService directExecutor = new DirectExecutorService();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestAdapter(dispatcher, directExecutor));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.OPTIONS,
                "/api/user/login"
        );
        request.headers().set(HttpHeaderNames.ORIGIN, "http://127.0.0.1:5173");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST");

        assertFalse(channel.writeInbound(request));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(null, response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void invalidJsonBodyReturnsBadRequestBeforeDispatch() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        ExecutorService directExecutor = new DirectExecutorService();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestAdapter(dispatcher, directExecutor));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/api/user/register",
                Unpooled.copiedBuffer("{bad-json", StandardCharsets.UTF_8)
        );
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);

        assertFalse(channel.writeInbound(request));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        Map<String, Object> body = readBody(response);
        assertEquals(ImErrorCode.BAD_REQUEST.getCode(), body.get("code"));
        assertEquals(ImErrorCode.BAD_REQUEST.getMessage(), body.get("msg"));
        assertEquals("invalid json body", body.get("detail"));
    }

    @Test
    void rejectedDispatchReturnsServiceUnavailable() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestAdapter(dispatcher, new RejectingExecutorService()));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/api/user/info"
        );
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer token");

        assertFalse(channel.writeInbound(request));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        Map<String, Object> body = readBody(response);
        assertEquals(ImErrorCode.MQ_UNAVAILABLE.getCode(), body.get("code"));
        assertEquals(ImErrorCode.MQ_UNAVAILABLE.getMessage(), body.get("msg"));
    }

    @Test
    void closedAdmissionReturnsServiceUnavailable() {
        ApiDispatcher dispatcher = new ApiDispatcher();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestAdapter(
                dispatcher, new DirectExecutorService(), new ClosedAdmission()));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/api/user/info"
        );

        assertFalse(channel.writeInbound(request));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        Map<String, Object> body = readBody(response);
        assertEquals(ImErrorCode.MQ_UNAVAILABLE.getCode(), body.get("code"));
        assertEquals(ImErrorCode.MQ_UNAVAILABLE.getMessage(), body.get("msg"));
    }

    private static Map<String, Object> readBody(FullHttpResponse response) {
        try {
            return MAPPER.readValue(response.content().toString(StandardCharsets.UTF_8), MAP_TYPE);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static class DirectExecutorService extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static class RejectingExecutorService extends DirectExecutorService {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("executor stopped");
        }
    }

    private static class ClosedAdmission implements RequestAdmission {
        @Override
        public RequestScope enter() {
            throw new InfrastructureException(ImErrorCode.MQ_UNAVAILABLE, "closed");
        }

        @Override
        public void open() {
        }

        @Override
        public void closeAndDrain(java.time.Duration timeout) {
        }

        @Override
        public boolean isOpen() {
            return false;
        }
    }
}

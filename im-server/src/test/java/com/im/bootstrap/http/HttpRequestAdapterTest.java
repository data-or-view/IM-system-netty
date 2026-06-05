package com.im.bootstrap.http;

import com.im.core.dispatcher.ApiDispatcher;
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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpRequestAdapterTest {

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
        request.headers().set(HttpHeaderNames.ORIGIN, "http://127.0.0.1:5173");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.headers().set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-request-id");

        assertFalse(channel.writeInbound(request));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("*", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        String allowHeaders = response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS);
        assertNotNull(allowHeaders);
        assertEquals(true, allowHeaders.toLowerCase().contains("x-request-id"));
        assertEquals("X-Request-Id", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS));
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
}

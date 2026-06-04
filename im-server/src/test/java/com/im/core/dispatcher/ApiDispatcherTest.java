package com.im.core.dispatcher;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestHandler;
import com.im.api.ResponseWriter;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.BusinessException;
import com.im.common.exception.InfrastructureException;
import com.im.common.exception.ImException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ApiDispatcher} 拦截器链 + handler 调度测试。
 */
class ApiDispatcherTest {

    private final List<String> log = new CopyOnWriteArrayList<>();

    private final ApiDispatcher dispatcher = new ApiDispatcher();

    /** 记录写回结果 */
    private final CapturingResponseWriter responseWriter = new CapturingResponseWriter();

    private ApiRequest request(Operation op) {
        return new ApiRequest(op, Map.of(), Map.of(), responseWriter, null);
    }

    // ── Tests ──

    @Test
    void noInterceptorsHandlerExecutes() {
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            log.add("handler:" + req.operation());
            return "ok";
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(List.of("handler:heartbeat"), log);
        assertEquals("ok", responseWriter.lastResult);
    }

    @Test
    void noHandlerReturnsNotFound() {
        dispatcher.dispatch(request(Operation.LOGIN));
        assertNotNull(responseWriter.lastError);
        assertEquals(ImErrorCode.NOT_FOUND, responseWriter.lastError);
    }

    @Test
    void interceptorPassesThrough() {
        dispatcher.addInterceptor(new LoggingInterceptor("A"));
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            log.add("handler:" + req.operation());
            return "ok";
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(List.of("pre:A:heartbeat", "handler:heartbeat", "after:A:heartbeat"), log);
    }

    @Test
    void interceptorBlocksRequest() {
        dispatcher.addInterceptor(new ApiInterceptor() {
            @Override public String name() { return "Blocker"; }
            @Override public boolean preHandle(ApiRequest request) {
                log.add("blocked:" + request.operation());
                return false;
            }
            @Override public void afterCompletion(ApiRequest request, Object result, Exception error) {
                // 阻断的拦截器不触发自己的 afterComplete
            }
        });
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            log.add("handler:" + req.operation());
            return "ok";
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(List.of("blocked:heartbeat"), log);
        assertFalse(log.stream().anyMatch(s -> s.startsWith("handler:")),
                "handler should not execute when blocked");
        assertEquals(ImErrorCode.FORBIDDEN, responseWriter.lastError);
    }

    @Test
    void middleInterceptorBlocks() {
        dispatcher.addInterceptor(new LoggingInterceptor("A"));

        // B 阻断
        dispatcher.addInterceptor(new LoggingInterceptor("B") {
            @Override
            public boolean preHandle(ApiRequest request) {
                log.add("blocked:B:" + request.operation());
                return false;
            }
        });

        dispatcher.addInterceptor(new LoggingInterceptor("C"));
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            log.add("handler:" + req.operation());
            return "ok";
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        // A 通过了 preHandle → afterComplete(A) 在反序时触发
        // B 阻断 → 不触发自己的 afterComplete，也不执行 C 的 preHandle
        assertEquals(3, log.size());
        assertEquals("pre:A:heartbeat", log.get(0));
        assertEquals("blocked:B:heartbeat", log.get(1));
        assertEquals("after:A:heartbeat", log.get(2));
    }

    @Test
    void allPassThenHandlerThenAfterCompleteInReverse() {
        dispatcher.addInterceptor(new LoggingInterceptor("A"));
        dispatcher.addInterceptor(new LoggingInterceptor("B"));
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            log.add("handler:" + req.operation());
            return "ok";
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        // pre(A) → pre(B) → handler → after(B) → after(A)
        assertEquals(5, log.size());
        assertEquals("pre:A:heartbeat", log.get(0));
        assertEquals("pre:B:heartbeat", log.get(1));
        assertEquals("handler:heartbeat", log.get(2));
        assertEquals("after:B:heartbeat", log.get(3));
        assertEquals("after:A:heartbeat", log.get(4));
    }

    @Test
    void handlerExceptionPropagatedToAfterComplete() {
        List<Exception> captured = new ArrayList<>();

        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            throw new RuntimeException("test error");
        });

        dispatcher.addInterceptor(new ApiInterceptor() {
            @Override public String name() { return "ExCatcher"; }
            @Override public boolean preHandle(ApiRequest request) { return true; }
            @Override
            public void afterCompletion(ApiRequest request, Object result, Exception ex) {
                captured.add(ex);
            }
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(1, captured.size());
        assertNotNull(captured.get(0));
        assertEquals("test error", captured.get(0).getMessage());
        // 非 ImException 的 detail 不应泄露给客户端
        assertNull(responseWriter.lastErrorDetail);
    }

    @Test
    void imExceptionPreservesDetail() {
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            throw new ImException(ImErrorCode.BAD_REQUEST, "field userId is required");
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(ImErrorCode.BAD_REQUEST, responseWriter.lastError);
        assertEquals("field userId is required", responseWriter.lastErrorDetail);
    }

    @Test
    void businessExceptionReturnsSafeMessage() {
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            throw new BusinessException(ImErrorCode.FORBIDDEN, "conversation not readable");
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(ImErrorCode.FORBIDDEN, responseWriter.lastError);
        assertEquals("conversation not readable", responseWriter.lastErrorDetail);
    }

    @Test
    void infrastructureExceptionHidesInternalDetail() {
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            throw new InfrastructureException(ImErrorCode.MQ_UNAVAILABLE, "redis xadd timeout");
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(ImErrorCode.MQ_UNAVAILABLE, responseWriter.lastError);
        assertNull(responseWriter.lastErrorDetail);
    }

    @Test
    void interceptorImExceptionPropagatesToClient() {
        dispatcher.addInterceptor(new ApiInterceptor() {
            @Override public String name() { return "Auth"; }
            @Override public boolean preHandle(ApiRequest request) {
                throw new ImException(ImErrorCode.UNAUTHORIZED, "token expired");
            }
            @Override public void afterCompletion(ApiRequest request, Object result, Exception error) {
                log.add("afterComplete:" + error);
            }
        });
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            log.add("handler:" + req.operation());
            return "ok";
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        // 调度器应写出 UNAUTHORIZED 错误码和 detail
        assertEquals(ImErrorCode.UNAUTHORIZED, responseWriter.lastError);
        assertEquals("token expired", responseWriter.lastErrorDetail);
        // 阻断的拦截器不触发自己的 afterComplete（同 return false 语义）
        assertFalse(log.stream().anyMatch(s -> s.startsWith("afterComplete")),
                "throwing interceptor's afterComplete should not be called");
        // handler 不应执行
        assertFalse(log.stream().anyMatch(s -> s.startsWith("handler:")),
                "handler should not execute when interceptor throws");
    }

    @Test
    void interceptorImExceptionAfterCompleteForPassed() {
        // A 通过，B 抛 ImException
        dispatcher.addInterceptor(new LoggingInterceptor("A"));
        dispatcher.addInterceptor(new ApiInterceptor() {
            @Override public String name() { return "Thrower"; }
            @Override public boolean preHandle(ApiRequest request) {
                throw new ImException(ImErrorCode.UNAUTHORIZED, "token expired");
            }
            @Override public void afterCompletion(ApiRequest request, Object result, Exception error) {
                log.add("afterComplete:" + error);
            }
        });

        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            log.add("handler:" + req.operation());
            return "ok";
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        // A 已通过，应被反向回调 afterComplete
        assertEquals(2, log.size());
        assertEquals("pre:A:heartbeat", log.get(0));
        assertEquals("after:A:heartbeat", log.get(1));
    }

    // ── 自定义异常处理器 ──

    @Test
    void customExceptionHandlerCalled() {
        dispatcher.registerExceptionHandler(IllegalArgumentException.class, (e, req) -> {
            responseWriter.writeError(ImErrorCode.BAD_REQUEST, "custom: " + e.getMessage());
        });

        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            throw new IllegalArgumentException("negative id");
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(ImErrorCode.BAD_REQUEST, responseWriter.lastError);
        assertEquals("custom: negative id", responseWriter.lastErrorDetail);
    }

    @Test
    void customExceptionHandlerBySuperclass() {
        // 注册父类型 handler，子类异常应能匹配
        dispatcher.registerExceptionHandler(RuntimeException.class, (e, req) -> {
            responseWriter.writeError(ImErrorCode.INTERNAL_ERROR, "runtime: " + e.getMessage());
        });

        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            throw new IllegalArgumentException("bad arg");
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(ImErrorCode.INTERNAL_ERROR, responseWriter.lastError);
        assertEquals("runtime: bad arg", responseWriter.lastErrorDetail);
    }

    @Test
    void customExceptionHandlerNotCalledForDifferentType() {
        dispatcher.registerExceptionHandler(IllegalArgumentException.class, (e, req) -> {
            responseWriter.writeError(ImErrorCode.BAD_REQUEST, "custom");
        });

        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            throw new IllegalStateException("state error");
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        // IllegalStateException 不匹配 IllegalArgumentException handler → 走默认逻辑（detail = null）
        assertEquals(ImErrorCode.INTERNAL_ERROR, responseWriter.lastError);
        assertNull(responseWriter.lastErrorDetail);
    }

    @Test
    void customExceptionHandlerFailureFallsBackToInternalError() {
        dispatcher.registerExceptionHandler(IllegalArgumentException.class, (e, req) -> {
            throw new RuntimeException("custom handler failed");
        });

        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            throw new IllegalArgumentException("bad arg");
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(ImErrorCode.INTERNAL_ERROR, responseWriter.lastError);
        assertNull(responseWriter.lastErrorDetail);
    }

    @Test
    void afterCompletionReceivesHandlerResult() {
        List<Object> captured = new ArrayList<>();

        dispatcher.addInterceptor(new ApiInterceptor() {
            @Override public boolean preHandle(ApiRequest request) { return true; }
            @Override public void afterCompletion(ApiRequest request, Object result, Exception error) {
                captured.add(result);
            }
        });
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> "ok");

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(List.of("ok"), captured);
    }

    @Test
    void dispatcherDoesNotAutoWriteWhenHandlerAlreadyCommittedResponse() {
        dispatcher.registerHandler(Operation.HEARTBEAT, req -> {
            req.responseWriter().writeError(ImErrorCode.BAD_REQUEST, "written by handler");
            return "should-not-be-written";
        });

        dispatcher.dispatch(request(Operation.HEARTBEAT));

        assertEquals(1, responseWriter.writeCount);
        assertEquals(ImErrorCode.BAD_REQUEST, responseWriter.lastError);
        assertEquals("written by handler", responseWriter.lastErrorDetail);
        assertNull(responseWriter.lastResult);
    }

    // ── 辅助 ──

    private class LoggingInterceptor implements ApiInterceptor {
        private final String name;

        LoggingInterceptor(String name) { this.name = name; }

        @Override
        public String name() { return name; }

        @Override
        public boolean preHandle(ApiRequest request) {
            log.add("pre:" + name + ":" + request.operation());
            return true;
        }

        @Override
        public void afterCompletion(ApiRequest request, Object result, Exception error) {
            log.add("after:" + name + ":" + request.operation());
        }
    }

    /** 捕获 write 调用的 ResponseWriter */
    private static class CapturingResponseWriter implements ResponseWriter {
        Object lastResult;
        ImErrorCode lastError;
        String lastErrorDetail;
        int writeCount;

        @Override
        public void write(Object result) {
            this.writeCount++;
            this.lastResult = result;
        }

        @Override
        public void writeError(ImErrorCode code, String detail) {
            this.writeCount++;
            this.lastError = code;
            this.lastErrorDetail = detail;
        }

        @Override
        public boolean isCommitted() {
            return writeCount > 0;
        }
    }
}

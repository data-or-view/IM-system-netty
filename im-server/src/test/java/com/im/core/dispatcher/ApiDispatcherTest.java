package com.im.core.dispatcher;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestHandler;
import com.im.api.ResponseWriter;
import com.im.common.enums.ImErrorCode;
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

        @Override
        public void write(Object result) {
            this.lastResult = result;
        }

        @Override
        public void writeError(ImErrorCode code, String detail) {
            this.lastError = code;
            this.lastErrorDetail = detail;
        }
    }
}

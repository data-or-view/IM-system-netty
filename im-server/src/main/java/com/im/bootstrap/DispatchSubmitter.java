package com.im.bootstrap;

import com.im.api.ApiRequest;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.dispatcher.ApiDispatcher;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Adapter 层统一提交分发任务，避免线程池关闭时异常冒到 Netty EventLoop。
 */
public final class DispatchSubmitter {
    private DispatchSubmitter() {
    }

    public static void submit(ApiDispatcher dispatcher, ExecutorService executor,
                              ApiRequest request, Logger log) {
        submit(dispatcher, executor, AlwaysOpenRequestAdmission.INSTANCE, request, log);
    }

    public static void submit(ApiDispatcher dispatcher, ExecutorService executor,
                              RequestAdmission admission, ApiRequest request, Logger log) {
        try {
            executor.execute(() -> {
                try (RequestScope ignored = admission.enter()) {
                    dispatcher.dispatch(request);
                } catch (ImException e) {
                    request.responseWriter().writeError(e.getErrorCode(), e.getSafeMessage());
                } catch (Exception e) {
                    log.error("Dispatch failed: op={}", request.operation(), e);
                    request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR, null);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Dispatch rejected: op={}", request.operation(), e);
            request.responseWriter().writeError(ImErrorCode.MQ_UNAVAILABLE, "server busy");
        }
    }

    private enum AlwaysOpenRequestAdmission implements RequestAdmission {
        INSTANCE;

        @Override
        public RequestScope enter() {
            return () -> {
            };
        }

        @Override
        public void open() {
        }

        @Override
        public void closeAndDrain(Duration timeout) {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}

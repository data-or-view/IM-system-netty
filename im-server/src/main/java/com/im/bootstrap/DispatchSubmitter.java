package com.im.bootstrap;

import com.im.api.ApiRequest;
import com.im.common.enums.ImErrorCode;
import com.im.core.dispatcher.ApiDispatcher;
import org.slf4j.Logger;

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
        try {
            executor.execute(() -> {
                try {
                    dispatcher.dispatch(request);
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
}

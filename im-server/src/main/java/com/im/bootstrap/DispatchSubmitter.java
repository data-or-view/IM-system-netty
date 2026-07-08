package com.im.bootstrap;

import com.im.api.ApiRequest;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.RequestObservability;
import com.im.core.observability.StructuredLog;
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
                    log.warn(StructuredLog.event(LogEvents.DISPATCH_REJECTED,
                            LogFields.NODE_ID, RequestObservability.nodeId(request),
                            LogFields.REQUEST_ID, RequestObservability.requestId(request),
                            LogFields.TRACE_ID, RequestObservability.traceId(request),
                            LogFields.OPERATION, RequestObservability.operation(request),
                            LogFields.PROTOCOL, RequestObservability.protocol(request),
                            LogFields.CLIENT_IP, RequestObservability.clientIp(request),
                            LogFields.ERROR_CODE, e.getErrorCode().getCode(),
                            LogFields.REASON, e.getSafeMessage()));
                    request.responseWriter().writeError(e.getErrorCode(), e.getSafeMessage());
                } catch (Exception e) {
                    log.error(StructuredLog.event(LogEvents.DISPATCH_FAILED,
                            LogFields.NODE_ID, RequestObservability.nodeId(request),
                            LogFields.REQUEST_ID, RequestObservability.requestId(request),
                            LogFields.TRACE_ID, RequestObservability.traceId(request),
                            LogFields.OPERATION, RequestObservability.operation(request),
                            LogFields.PROTOCOL, RequestObservability.protocol(request),
                            LogFields.CLIENT_IP, RequestObservability.clientIp(request),
                            LogFields.ERROR_CODE, ImErrorCode.INTERNAL_ERROR.getCode(),
                            LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName()), e);
                    request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR, null);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn(StructuredLog.event(LogEvents.DISPATCH_REJECTED,
                    LogFields.NODE_ID, RequestObservability.nodeId(request),
                    LogFields.REQUEST_ID, RequestObservability.requestId(request),
                    LogFields.TRACE_ID, RequestObservability.traceId(request),
                    LogFields.OPERATION, RequestObservability.operation(request),
                    LogFields.PROTOCOL, RequestObservability.protocol(request),
                    LogFields.CLIENT_IP, RequestObservability.clientIp(request),
                    LogFields.ERROR_CODE, ImErrorCode.MQ_UNAVAILABLE.getCode(),
                    LogFields.REASON, "executor_rejected",
                    LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName()));
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

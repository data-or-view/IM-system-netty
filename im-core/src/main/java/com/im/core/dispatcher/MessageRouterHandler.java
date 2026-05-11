package com.im.core.dispatcher;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.api.IMInterceptor;
import com.im.api.IMessageHandler;
import com.im.core.util.IMExecutors;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 消息路由分发器，参考 RocketMQ 的 processorTable + pair 模式。
 *
 * 职责：
 *   ① 根据 CommandType 选择合适的 IMessageHandler
 *   ② 按消息类型分发到不同线程池（心跳走定时调度，业务走虚拟线程）
 *   ③ 拦截器链（preHandle → handler → afterComplete）
 *   ④ 全局异常处理（@ControllerAdvice 风格）
 *       handler 抛 ImException → 提取错误码 → 返回 ERROR 命令
 *       handler 抛 Exception → 返回 500 ERROR 命令
 *
 * 拦截器执行语义（同 Spring HandlerInterceptor）：
 *   按注册顺序执行 preHandle。任一返回 false → 阻断，已通过的拦截器反序回调 afterComplete。
 *   handler 执行后，所有通过的拦截器反序回调 afterComplete（在 finally 块中，异常也触发）。
 *
 * 线程模型：
 *   ╔════════════════╦══════════════════════════╦═══════════════════════╗
 *   ║ 请求类型       ║ 执行器                  ║ 线程模型             ║
 *   ╠════════════════╬══════════════════════════╬═══════════════════════╣
 *   ║ HEARTBEAT      ║ ScheduledExecutorService ║ 平台守护线程 1 条    ║
 *   ║ BUSINESS       ║ VirtualThreadExecutor     ║ 每任务一条虚拟线程   ║
 *   ╚════════════════╩══════════════════════════╩═══════════════════════╝
 */
@ChannelHandler.Sharable
public class MessageRouterHandler extends SimpleChannelInboundHandler<IMCommand> {

    private static final Logger log = LoggerFactory.getLogger(MessageRouterHandler.class);

    /** ERROR 响应头：错误码 */
    private static final String HEADER_ERR = "_err";

    /** ERROR 响应头：简要原因 */
    private static final String HEADER_REASON = "reason";

    /** 类型 → 处理器映射 */
    private final Map<CommandType, IMessageHandler> handlerMap = new HashMap<>();

    /** 类型 → 线程池映射 */
    private final Map<CommandType, ExecutorService> executorMap = new HashMap<>();

    /** 拦截器链 */
    private final List<IMInterceptor> interceptors = new ArrayList<>();

    /** 心跳处理：平台线程定时调度 */
    private final ScheduledExecutorService heartbeatExecutor;

    /** 业务处理：虚拟线程执行器 */
    private final ExecutorService businessExecutor;

    public MessageRouterHandler(List<IMessageHandler> handlers, int businessThreads) {
        this.heartbeatExecutor = IMExecutors.newScheduledExecutor("im-hb", 1);
        this.businessExecutor = IMExecutors.newVirtualThreadExecutor("im-business");

        // 注册所有 handler
        for (IMessageHandler handler : handlers) {
            for (CommandType type : handler.supportedTypes()) {
                if (handlerMap.containsKey(type)) {
                    log.warn("Duplicate handler for type {}, overwriting", type);
                }
                handlerMap.put(type, handler);
                // 心跳类消息走定时调度器，其余走虚拟线程
                executorMap.put(type, isHeartbeatType(type) ? heartbeatExecutor : businessExecutor);
            }
        }
    }

    /** 注册拦截器（追加到链尾） */
    public MessageRouterHandler addInterceptor(IMInterceptor interceptor) {
        interceptors.add(interceptor);
        log.info("Interceptor registered: {}", interceptor.name());
        return this;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, IMCommand msg) {
        IMessageHandler handler = handlerMap.get(msg.getType());

        if (handler == null) {
            log.warn("No handler for type: {}, seqId={}", msg.getType(), msg.getSeqId());
            sendError(ctx, msg, ImErrorCode.NOT_FOUND, "no handler for type: " + msg.getType());
            return;
        }

        ExecutorService executor = executorMap.getOrDefault(msg.getType(), businessExecutor);

        executor.execute(() -> processWithInterceptors(ctx, msg, handler));
    }

    /**
     * 执行拦截器链 + handler + 全局异常处理。
     *
     * 语义（同 Spring HandlerInterceptor + @ControllerAdvice）：
     *   1. preHandle 按注册顺序执行
     *   2. 任一返回 false / 抛异常 → 阻断（已通过的拦截器反序 afterComplete）+ 发 ERROR
     *   3. handler 抛异常 → 全局捕获 → 发 ERROR + 反序 afterComplete
     *   4. handler 正常 → 反序 afterComplete
     */
    private void processWithInterceptors(ChannelHandlerContext ctx, IMCommand msg, IMessageHandler handler) {
        int idx = 0;
        try {
            // ── preHandle 链 ──
            for (; idx < interceptors.size(); idx++) {
                IMInterceptor interceptor = interceptors.get(idx);
                try {
                    if (!interceptor.preHandle(ctx, msg)) {
                        log.debug("Interceptor '{}' blocked request type={}, seqId={}",
                                interceptor.name(), msg.getType(), msg.getSeqId());
                        // 阻断：已通过的拦截器反序回调
                        for (int i = idx - 1; i >= 0; i--) {
                            afterCompleteSafe(interceptors.get(i), ctx, msg, null);
                        }
                        sendError(ctx, msg, ImErrorCode.FORBIDDEN,
                                "blocked by interceptor: " + interceptor.name());
                        return;
                    }
                } catch (ImException e) {
                    log.warn("Interceptor '{}' preHandle rejected: {} {}", interceptor.name(),
                            e.getErrorCode().getCode(), e.getDetail());
                    for (int i = idx - 1; i >= 0; i--) {
                        afterCompleteSafe(interceptors.get(i), ctx, msg, e);
                    }
                    sendError(ctx, msg, e.getErrorCode(), e.getDetail());
                    return;
                } catch (Exception e) {
                    log.warn("Interceptor '{}' preHandle threw unexpected exception", interceptor.name(), e);
                    for (int i = idx - 1; i >= 0; i--) {
                        afterCompleteSafe(interceptors.get(i), ctx, msg, e);
                    }
                    sendError(ctx, msg, ImErrorCode.INTERNAL_ERROR,
                            "interceptor error: " + interceptor.name());
                    return;
                }
            }

            // ── handler 执行 + 全局异常捕获 ──
            Exception handlerEx = null;
            try {
                handler.handle(ctx, msg);
            } catch (ImException e) {
                handlerEx = e;
                log.warn("Handler rejected: {} {} type={}, seqId={}",
                        e.getCode(), e.getMessage(), msg.getType(), msg.getSeqId());
                sendError(ctx, msg, e.getErrorCode(), e.getDetail());
            } catch (Exception e) {
                handlerEx = e;
                log.error("Handler error: type={}, seqId={}", msg.getType(), msg.getSeqId(), e);
                sendError(ctx, msg, ImErrorCode.INTERNAL_ERROR, e.getMessage());
            } finally {
                // ── afterComplete 链（反序） ──
                for (int i = idx - 1; i >= 0; i--) {
                    afterCompleteSafe(interceptors.get(i), ctx, msg, handlerEx);
                }
            }
        } finally {
            // 确保任何未捕获的异常不会导致拦截器链漏调
        }
    }

    /** 发送错误响应给客户端 */
    private void sendError(ChannelHandlerContext ctx, IMCommand original, ImErrorCode errorCode, String detail) {
        try {
            IMCommand error = original.createAcknowledgement(CommandType.ERROR);
            error.putHeader(HEADER_ERR, String.valueOf(errorCode.getCode()));
            error.putHeader(HEADER_REASON, errorCode.getMessage());
            if (detail != null && !detail.isEmpty()) {
                error.putHeader("detail", detail);
            }
            ctx.writeAndFlush(error);
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }

    /** 安全调用 afterComplete（不对上层抛异常） */
    private void afterCompleteSafe(IMInterceptor interceptor, ChannelHandlerContext ctx,
                                   IMCommand msg, Exception ex) {
        try {
            interceptor.afterComplete(ctx, msg, ex);
        } catch (Exception e) {
            log.warn("Interceptor '{}' afterComplete threw: {}", interceptor.name(), e.getMessage());
        }
    }

    private static boolean isHeartbeatType(CommandType type) {
        return type == CommandType.HEARTBEAT || type == CommandType.HEARTBEAT_ACK;
    }

    public void shutdown() {
        heartbeatExecutor.shutdown();
        businessExecutor.shutdown();
    }

    /** 优雅关闭并等待任务完成（最多等 5 秒），测试中用于同步 */
    public void shutdownAndWait() {
        heartbeatExecutor.shutdown();
        businessExecutor.shutdown();
        try {
            heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS);
            businessExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

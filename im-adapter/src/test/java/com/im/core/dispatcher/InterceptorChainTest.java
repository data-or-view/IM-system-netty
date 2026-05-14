package com.im.core.dispatcher;

import com.im.api.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 拦截器链测试。
 *
 * 注意：MessageRouterHandler 的 dispatch 走异步线程池，
 * 每个 test 结束后通过 shutdown() 等待所有异步任务完成后再断言。
 */
class InterceptorChainTest {

    private EmbeddedChannel channel;
    private ChannelHandlerContext ctx;
    private MessageRouterHandler router;
    /** 用线程安全的列表，避免异步竞争 */
    private final List<String> log = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel();
        ctx = channel.pipeline().firstContext();

        List<IMessageHandler> handlers = List.of(new IMessageHandler() {
            @Override
            public void handle(ChannelHandlerContext ctx, IMCommand msg) {
                log.add("handler:" + msg.getSeqId());
            }

            @Override
            public Set<CommandType> supportedTypes() {
                return Set.of(CommandType.HEARTBEAT);
            }
        });

        router = new MessageRouterHandler(handlers, 2);
    }

    @AfterEach
    void tearDown() {
        router.shutdownAndWait();
    }

    @Test
    void noInterceptorsHandlerExecutes() {
        router.channelRead0(ctx, command(1, CommandType.HEARTBEAT));
        router.shutdownAndWait();
        assertEquals(List.of("handler:1"), log);
    }

    @Test
    void interceptorPassesThrough() {
        router.addInterceptor(new LoggingInterceptor("A"));

        router.channelRead0(ctx, command(1, CommandType.HEARTBEAT));
        router.shutdownAndWait();
        assertEquals(
                List.of("pre:A:1", "handler:1", "after:A:1"),
                log);
    }

    @Test
    void interceptorBlocksRequest() {
        router.addInterceptor(new IMInterceptor() {
            @Override
            public String name() { return "Blocker"; }

            @Override
            public boolean preHandle(ChannelHandlerContext ctx, IMCommand msg) {
                log.add("blocked:" + msg.getSeqId());
                return false;
            }

            @Override
            public void afterComplete(ChannelHandlerContext ctx, IMCommand msg, Exception ex) {
                // 永远不会触发（Spring 语义：阻断的拦截器不回调自己的 afterComplete）
            }
        });

        router.channelRead0(ctx, command(1, CommandType.HEARTBEAT));
        router.shutdownAndWait();
        assertEquals(List.of("blocked:1"), log);
        assertFalse(log.stream().anyMatch(s -> s.startsWith("handler:")),
                "handler should not execute when blocked");
    }

    @Test
    void middleInterceptorBlocks() {
        router.addInterceptor(new LoggingInterceptor("A"));

        // B 阻断
        router.addInterceptor(new LoggingInterceptor("B") {
            @Override
            public boolean preHandle(ChannelHandlerContext ctx, IMCommand msg) {
                log.add("blocked:B:" + msg.getSeqId());
                return false;
            }
        });

        router.addInterceptor(new LoggingInterceptor("C"));

        router.channelRead0(ctx, command(1, CommandType.HEARTBEAT));
        router.shutdownAndWait();

        // A 通过了 preHandle → afterComplete(A) 在反序时触发
        // B 阻断 → 不触发自己的 afterComplete，也不执行 C 的 preHandle
        // C 的 preHandle 没机会
        // handler 不执行
        assertEquals(3, log.size());
        assertEquals("pre:A:1", log.get(0));
        assertEquals("blocked:B:1", log.get(1));
        assertEquals("after:A:1", log.get(2));
    }

    @Test
    void allPassThenHandlerThenAfterCompleteInReverse() {
        router.addInterceptor(new LoggingInterceptor("A"));
        router.addInterceptor(new LoggingInterceptor("B"));

        router.channelRead0(ctx, command(1, CommandType.HEARTBEAT));
        router.shutdownAndWait();

        // pre(A) → pre(B) → handler → after(B) → after(A)
        assertEquals(5, log.size());
        assertEquals("pre:A:1", log.get(0));
        assertEquals("pre:B:1", log.get(1));
        assertEquals("handler:1", log.get(2));
        assertEquals("after:B:1", log.get(3));
        assertEquals("after:A:1", log.get(4));
    }

    @Test
    void handlerExceptionPropagatedToAfterComplete() {
        List<Exception> captured = new ArrayList<>();

        router = new MessageRouterHandler(List.of(new IMessageHandler() {
            @Override public void handle(ChannelHandlerContext ctx1, IMCommand msg) {
                throw new RuntimeException("test error");
            }
            @Override public Set<CommandType> supportedTypes() {
                return Set.of(CommandType.HEARTBEAT);
            }
        }), 2);

        router.addInterceptor(new IMInterceptor() {
            @Override public String name() { return "ExCatcher"; }
            @Override public boolean preHandle(ChannelHandlerContext ctx, IMCommand msg) { return true; }
            @Override
            public void afterComplete(ChannelHandlerContext ctx, IMCommand msg, Exception ex) {
                captured.add(ex);
            }
        });

        router.channelRead0(ctx, command(1, CommandType.HEARTBEAT));
        router.shutdownAndWait();

        assertEquals(1, captured.size());
        assertNotNull(captured.get(0));
        assertEquals("test error", captured.get(0).getMessage());
    }

    // ── 辅助 ──

    private static IMCommand command(int seqId, CommandType type) {
        IMCommand cmd = new IMCommand(type);
        cmd.setSeqId(seqId);
        return cmd;
    }

    private class LoggingInterceptor implements IMInterceptor {
        private final String name;

        LoggingInterceptor(String name) { this.name = name; }

        @Override
        public String name() { return name; }

        @Override
        public boolean preHandle(ChannelHandlerContext ctx, IMCommand msg) {
            log.add("pre:" + name + ":" + msg.getSeqId());
            return true;
        }

        @Override
        public void afterComplete(ChannelHandlerContext ctx, IMCommand msg, Exception ex) {
            log.add("after:" + name + ":" + msg.getSeqId());
        }
    }
}

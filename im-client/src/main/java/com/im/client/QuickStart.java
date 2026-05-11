package com.im.client;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.content.ContentType;
import com.im.api.content.TextContent;
import com.im.codec.ContentSerializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import com.im.codec.IMDecoder;
import com.im.codec.IMEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QuickStart 演示客户端。
 *
 * 登录后自动获取 token，挂载到后续所有请求的 Authorization header。
 * 支持：登录、发消息、收消息、/pull 拉历史、/quit 退出。
 *
 * 用法：
 *   QuickStart alice bob [host] [port]
 *   QuickStart bob alice [host] [port]
 */
public class QuickStart {

    private static final Logger log = LoggerFactory.getLogger(QuickStart.class);

    private final String userId;
    private final String serverHost;
    private final int serverPort;
    private final String targetUserId;

    private Channel channel;
    private NioEventLoopGroup group;
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final AtomicReference<String> tokenRef = new AtomicReference<>(null);

    public QuickStart(String userId, String targetUserId, String serverHost, int serverPort) {
        this.userId = userId;
        this.targetUserId = targetUserId;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public void start() throws Exception {
        group = new NioEventLoopGroup(1);

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                        ch.pipeline().addLast(new LoggingHandler(LogLevel.INFO));
                        ch.pipeline().addLast(new IMDecoder());
                        ch.pipeline().addLast(new IMEncoder());
                        ch.pipeline().addLast(new ClientHandler());
                    }
                });

        channel = bootstrap.connect(serverHost, serverPort).sync().channel();
        log.info("Connected to {}:{}", serverHost, serverPort);

        // 发送登录请求
        IMCommand login = new IMCommand(CommandType.LOGIN);
        login.putHeader("userId", userId);
        channel.writeAndFlush(login);
        log.info("Login request sent for user: {}", userId);

        connectedLatch.await();
    }

    /** 给 IMCommand 挂上 Authorization header（如果已获取到 token） */
    private IMCommand attachAuth(IMCommand cmd) {
        String token = tokenRef.get();
        if (token != null && !token.isEmpty()) {
            cmd.putHeader("Authorization", "Bearer " + token);
        }
        return cmd;
    }

    public void sendText(String text) {
        if (channel == null || !channel.isActive()) {
            log.warn("Not connected");
            return;
        }

        IMCommand msg = new IMCommand(CommandType.SINGLE_CHAT);
        msg.putHeader("fromUserId", userId);
        msg.putHeader("toUserId", targetUserId);
        msg.putHeader("_ct", ContentType.TEXT.name().toLowerCase());

        TextContent content = new TextContent(text);
        msg.setBody(ContentSerializer.toBytes(content));

        attachAuth(msg);
        channel.writeAndFlush(msg);
        log.info("Sent to {}: {}", targetUserId, text);
    }

    public void pullMessages(String conversationId) {
        IMCommand pull = new IMCommand(CommandType.PULL_MESSAGE);
        pull.putHeader("conversationId", conversationId);
        pull.putHeader("_ms_start", "0");
        pull.putHeader("_ms_end", "0");
        pull.putHeader("limit", "50");
        channel.writeAndFlush(attachAuth(pull));
        log.info("Pull request sent for conversation: {}", conversationId);
    }

    public void stop() {
        if (channel != null) channel.close();
        if (group != null) group.shutdownGracefully();
    }

    /**
     * 客户端消息处理器。
     */
    private class ClientHandler extends SimpleChannelInboundHandler<IMCommand> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, IMCommand msg) {
            switch (msg.getType()) {
                case LOGIN_ACK -> {
                    String status = msg.getHeader("status");
                    String token = msg.getHeader("token");
                    if (token != null && !token.isEmpty()) {
                        tokenRef.set(token);
                        log.info("<< LOGIN ACK: status={}, userId={}, token={}",
                                status, userId, token.substring(0, Math.min(20, token.length())) + "...");
                    } else {
                        log.info("<< LOGIN ACK: status={}, userId={}", status, userId);
                    }
                    connectedLatch.countDown();
                }
                case SINGLE_CHAT -> {
                    String from = msg.getHeader("fromUserId");
                    String seq = msg.getHeader("_ms");
                    String conv = msg.getHeader("conversationId");
                    String body = msg.getBody() != null ? new String(msg.getBody()) : "(empty)";
                    log.info("<< MSG from={} seq={} conv={}: {}",
                            from, seq, conv, body);
                    System.out.println("\n[收到消息] from=" + from + ": " + body + "\n");
                }
                case SINGLE_CHAT_ACK -> {
                    log.info("<< MSG ACK: seq={}, status={}",
                            msg.getHeader("_ms"), msg.getHeader("status"));
                }
                case PULL_MESSAGE_ACK -> {
                    String conv = msg.getHeader("conversationId");
                    String count = msg.getHeader("_count");
                    String maxSeq = msg.getHeader("_max_seq");
                    log.info("<< PULL ACK: conv={}, count={}, maxSeq={}",
                            conv, count, maxSeq);
                    if (msg.getBody() != null) {
                        log.info("<< PULL messages body: {} bytes", msg.getBody().length);
                    }
                }
                case ERROR -> {
                    log.warn("<< ERROR: _err={}, reason={}, detail={}",
                            msg.getHeader("_err"),
                            msg.getHeader("reason"),
                            msg.getHeader("detail"));
                }
                default -> log.info("<< {}: headers={}", msg.getType(), msg.getHeaders());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Client error", cause);
        }
    }

    // ========== main ==========

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: QuickStart <userId> <targetUserId> [host] [port]");
            System.err.println("Example: QuickStart userA userB");
            System.err.println("    或： QuickStart userB userA");
            System.exit(1);
        }

        String userId = args[0];
        String targetUserId = args[1];
        String host = args.length > 2 ? args[2] : "127.0.0.1";
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 8080;

        QuickStart client = new QuickStart(userId, targetUserId, host, port);
        client.start();

        // 自动发送一条问候消息
        client.sendText("Hello from " + userId + "!");

        // 等待 1 秒后拉取消息
        Thread.sleep(1000);
        String conversationId = "single_" +
                (userId.compareTo(targetUserId) <= 0 ? userId : targetUserId) + "_" +
                (userId.compareTo(targetUserId) <= 0 ? targetUserId : userId);
        client.pullMessages(conversationId);

        // 交互式输入
        System.out.println("\n=== Interactive mode ===");
        System.out.println("Type message and press Enter to send.");
        System.out.println("Type /pull to pull messages.");
        System.out.println("Type /quit to exit.\n");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            String line = scanner.nextLine();
            if (line == null) break;
            if (line.equalsIgnoreCase("/quit")) break;
            if (line.equalsIgnoreCase("/pull")) {
                client.pullMessages(conversationId);
                continue;
            }
            client.sendText(line);
        }

        client.stop();
        System.exit(0);
    }
}

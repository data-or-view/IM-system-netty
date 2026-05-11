package com.im.client.handler;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.client.IMClient;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 客户端消息处理器，接收从服务端发来的消息并分发。
 *
 * 处理的消息类型：
 *   LOGIN_ACK    → 标记登录成功
 *   HEARTBEAT_ACK → 心跳成功（可忽略，无需特殊处理）
 *   SINGLE_CHAT   → 收到其他用户发来的消息（server 直接转发）
 *   ERROR        → 处理错误响应
 *
 * ACK 配对：所有 ACK 类型（LOGIN_ACK, HEARTBEAT_ACK, SINGLE_CHAT_ACK 等）
 * 由 IMClient.processAck() 统一处理 → PendingAcknowledgementManager.onAckReceived()
 */
@ChannelHandler.Sharable
public class ClientMessageHandler extends SimpleChannelInboundHandler<IMCommand> {

    private static final Logger log = LoggerFactory.getLogger(ClientMessageHandler.class);

    private final IMClient client;

    /**
     * 客户端消息回调接口。调用方通过 IMClient.setMessageCallback() 注册。
     */
    @FunctionalInterface
    public interface MessageCallback {
        void onMessage(IMCommand msg);
    }

    private volatile MessageCallback callback;

    public ClientMessageHandler(IMClient client) {
        this.client = client;
    }

    public void setCallback(MessageCallback callback) {
        this.callback = callback;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, IMCommand msg) {
        // 先尝试 ACK 配对（所有 ACK 类型都走这里）
        if (client.tryAck(msg)) {
            return; // ACK 已被消费（future.complete）
        }

        // 非 ACK 消息 → 分发给业务层
        switch (msg.getType()) {
            case LOGIN_ACK -> {
                String status = msg.getHeader("status");
                log.info("Login result: status={}", status);
                client.onLoginResult("OK".equals(status));
            }
            case SINGLE_CHAT -> {
                log.info("Received message from {}: messageId={}, body={}",
                        msg.getHeader("fromUserId"), msg.getMessageId(),
                        msg.getBody() != null ? new String(msg.getBody(), StandardCharsets.UTF_8) : "");
                if (callback != null) {
                    callback.onMessage(msg);
                }
            }
            case ERROR -> {
                log.warn("Server error: seqId={}, reason={}", msg.getSeqId(), msg.getHeader("reason"));
            }
            default -> {
                log.debug("Unhandled message: type={}, seqId={}", msg.getType(), msg.getSeqId());
            }
        }
    }
}

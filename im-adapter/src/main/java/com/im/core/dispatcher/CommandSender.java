package com.im.core.dispatcher;

import com.im.api.ICommandSender;
import com.im.api.IMCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 消息发送器实现，提供三种发送模式。
 *
 * 参考 RocketMQ 的 invokeSync / invokeAsync / invokeOneway。
 *
 * sendAndAck 使用 PendingAcknowledgementManager 实现请求-响应配对：
 *   发送时注册 seqId → future
 *   收到响应时 future.complete()
 *   超时或连接断开时 future.completeExceptionally()
 */
public class CommandSender implements ICommandSender {

    private static final Logger log = LoggerFactory.getLogger(CommandSender.class);

    /** 默认 ACK 超时 */
    public static final long DEFAULT_ACK_TIMEOUT_MS = 5000;

    private final PendingAcknowledgementManager pendingAcknowledgementManager;

    public CommandSender(PendingAcknowledgementManager pendingAcknowledgementManager) {
        this.pendingAcknowledgementManager = pendingAcknowledgementManager;
    }

    @Override
    public void send(Channel channel, IMCommand command) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(command);
        } else {
            log.warn("Cannot send, channel inactive: {}", command);
        }
    }

    @Override
    public CompletableFuture<IMCommand> sendAndAck(Channel channel, IMCommand command, long timeoutMs) {
        CompletableFuture<IMCommand> future = new CompletableFuture<>();
        pendingAcknowledgementManager.register(command.getSeqId(), future, timeoutMs);

        if (channel != null && channel.isActive()) {
            // 发送失败时立即 completeExceptionally
            channel.writeAndFlush(command).addListener(f -> {
                if (!f.isSuccess() && !future.isDone()) {
                    pendingAcknowledgementManager.onAckReceived(command); // 先移除
                    future.completeExceptionally(f.cause());
                }
            });
        } else {
            pendingAcknowledgementManager.onAckReceived(command); // 先移除
            future.completeExceptionally(new IllegalStateException("Channel inactive"));
        }

        return future;
    }

    @Override
    public void reply(ChannelHandlerContext ctx, IMCommand request, IMCommand response) {
        // ACK 自动继承 request 的 seqId
        if (response.getSeqId() == 0) {
            response.setSeqId(request.getSeqId());
        }
        ctx.writeAndFlush(response);
    }
}

package com.im.api;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.CompletableFuture;

/**
 * 消息发送器接口，封装同步/异步/单向三种发送模式。
 *
 * 参考 RocketMQ 的 invokeSync / invokeAsync / invokeOneway 三种模式：
 *   单向（fire-and-forget）→ send
 *   同步等待 ACK         → sendAndAck （等待到 ack 或超时）
 *   回复                  → reply（用于 Handler 回复请求方）
 */
public interface ICommandSender {

    /**
     * 单向发送，不等待响应。
     */
    void send(Channel channel, IMCommand command);

    /**
     * 发送消息并等待 ACK。
     *
     * @param channel   目标 Channel
     * @param command   要发送的消息（必须包含 seqId）
     * @param timeoutMs 超时毫秒
     * @return ACK 消息的 CompletableFuture
     */
    CompletableFuture<IMCommand> sendAndAck(Channel channel, IMCommand command, long timeoutMs);

    /**
     * 回复请求（自动使用请求的 seqId）。
     */
    void reply(ChannelHandlerContext ctx, IMCommand request, IMCommand response);
}

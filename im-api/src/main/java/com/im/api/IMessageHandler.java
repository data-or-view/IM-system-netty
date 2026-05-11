package com.im.api;

import io.netty.channel.ChannelHandlerContext;

import java.util.Set;

/**
 * 消息处理器接口。参考 RocketMQ 的 NettyRequestProcessor。
 *
 * 每个具体业务逻辑（登录、心跳、聊天）实现此接口，
 * 通过 supportedTypes() 声明自己处理哪些 CommandType，
 * 由 MessageRouter 根据消息类型路由到对应的处理器。
 */
public interface IMessageHandler {

    /**
     * 处理消息。
     *
     * @param ctx  Channel 上下文
     * @param msg  解码后的消息
     */
    void handle(ChannelHandlerContext ctx, IMCommand msg) throws Exception;

    /**
     * 返回此处理器支持的消息类型集合。
     * MessageRouter 据此决定消息路由目标。
     */
    Set<CommandType> supportedTypes();
}

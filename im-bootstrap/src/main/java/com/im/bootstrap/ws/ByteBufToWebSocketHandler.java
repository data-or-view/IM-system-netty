package com.im.bootstrap.ws;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.List;

/**
 * WebSocket 出站编码器：ByteBuf → BinaryWebSocketFrame。
 *
 * IMEncoder 输出的是标准的 0xACAC 二进制帧（ByteBuf），
 * 对于 WebSocket 连接需要包装为 BinaryWebSocketFrame 发送。
 *
 * 在 WebSocket pipeline 中放在 IMEncoder 之后：
 *   p.addLast(new IMEncoder());
 *   p.addLast(new ByteBufToWebSocketHandler());
 *
 * TCP pipeline 中不加此 handler，ByteBuf 直接写入 TCP socket。
 */
@ChannelHandler.Sharable
public class ByteBufToWebSocketHandler extends MessageToMessageEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        out.add(new BinaryWebSocketFrame(msg.retain()));
    }
}

package com.im.bootstrap.ws;

import com.im.api.IMCommand;
import com.im.codec.IMDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * WebSocket 解码器：BinaryWebSocketFrame → IMCommand。
 *
 * WebSocket 帧总是完整的（无 TCP 粘包问题），
 * 直接从 BinaryWebSocketFrame.content() 提取 ByteBuf 后委托给
 * {@link IMDecoder#decodeFrame(ByteBuf)} 解析。
 *
 * 只处理 BinaryWebSocketFrame，Text/Ping/Pong/Close 帧由
 * WebSocketServerProtocolHandler 自动处理。
 */
public class WebSocketIMDecoder extends MessageToMessageDecoder<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketIMDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) {
        if (!(frame instanceof BinaryWebSocketFrame)) {
            return; // non-binary frames handled by WebSocketServerProtocolHandler
        }

        ByteBuf content = frame.content();
        int readable = content.readableBytes();
        log.debug("WS frame received: {} bytes", readable);

        if (readable < IMDecoder.FIXED_HEADER_LENGTH) {
            log.warn("WebSocket frame too short: {} bytes", readable);
            ctx.close();
            return;
        }

        // 打印前几个字节用于调试
        int markIdx = content.readerIndex();
        short magic = content.readShort();
        content.readerIndex(markIdx);
        log.debug("WS frame magic=0x{}", Integer.toHexString(magic & 0xFFFF));

        try {
            IMCommand cmd = IMDecoder.decodeFrame(content);
            if (cmd != null) {
                log.debug("WS frame decoded: type={}, seqId={}", cmd.getType(), cmd.getSeqId());
                out.add(cmd);
            } else {
                log.warn("WS frame decode returned null (incomplete frame)");
            }
        } catch (CorruptedFrameException e) {
            log.warn("Invalid binary frame, closing ws: {}", e.getMessage());
            ctx.close();
        }
    }
}

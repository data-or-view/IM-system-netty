package com.im.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMCommand;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 编码器：IMCommand → 二进制字节流（新帧结构）。
 *
 * 新帧结构（固定头 10 字节 + 变长 body）：
 * ┌─────────┬──────────┬──────────┬──────────────┬──────────────┬──────────────────┐
 * │  magic   │ version │  flags   │   bodyLen     │   headerLen   │   bodyContent    │
 * │  2 bytes │ 1 byte  │ 1 byte   │   4 bytes     │   2 bytes     │   bodyLen - hL    │
 * └─────────┴──────────┴──────────┴──────────────┴──────────────┴──────────────────┘
 *
 * bodyLen = headerLen +  bodyContent.length
 * bodyContent = headerBytes(JSON) + contentBody(raw)
 *
 * 【为什么改】旧协议 body 取「剩余所有字节」，粘包时会把下条消息吞掉。
 * 加上 bodyLen 后解码器精确知道帧边界，不会越界读取。
 */
public class IMEncoder extends MessageToByteEncoder<IMCommand> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定头长度：magic(2) + version(1) + flags(1) + bodyLen(4) + headerLen(2) */
    public static final int FIXED_HEADER_LENGTH = 10;

    @Override
    protected void encode(ChannelHandlerContext ctx, IMCommand msg, ByteBuf out) {
        // 1. 序列化 headers 为 JSON
        byte[] headerBytes = serializeToJsonBytes(msg);

        // 2. body 内容长度
        byte[] body = msg.getBody();
        int contentLen = body != null ? body.length : 0;

        // 3. bodyLen = headerBytes + contentBody
        int bodyLen = headerBytes.length + contentLen;

        // 4. 写固定头（10 字节）
        out.writeShort(IMCommand.MAGIC);    // magic: 2 bytes
        out.writeByte(msg.getVersion());    // version: 1 byte
        out.writeByte(msg.getFlags());      // flags: 1 byte
        out.writeInt(bodyLen);              // bodyLen: 4 bytes
        out.writeShort(headerBytes.length); // headerLen: 2 bytes

        // 5. 写 body 内容
        out.writeBytes(headerBytes);
        if (contentLen > 0) {
            out.writeBytes(body);
        }
    }

    /**
     * 将 IMCommand 的所有字段（协议 + 自定义）序列化为 JSON 字节。
     */
    static byte[] serializeToJsonBytes(IMCommand command) {
        try {
            return MAPPER.writeValueAsBytes(command.toJsonMap());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize IMCommand", e);
        }
    }
}

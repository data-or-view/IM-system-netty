package com.im.codec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMCommand;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 解码器：二进制字节流 → IMCommand（新帧结构）。
 *
 * 解码流程：
 *   ① 检查可读字节 ≥ 10（固定头）→ 否则等待
 *   ② 读 magic 并校验 → 非法关闭连接
 *   ③ 读 version、flags、bodyLen、headerLen
 *   ④ 检查 bodyLen 在合法范围 → 否则关闭（防攻击）
 *   ⑤ 检查可读字节 ≥ bodyLen → 否则等待（精确分帧！）
 *   ⑥ 读 headerLen 字节 → JSON 解析 → fromJsonMap()
 *   ⑦ 剩余 bodyLen - headerLen 字节作为 body
 *
 * 本解码器适用于 TCP 传输（ByteToMessageDecoder 自动处理粘包）。
 * 共享的二进制帧解析逻辑在 {@link #decodeFrame(ByteBuf)} 静态方法中，
 * 供 {@link WebSocketIMDecoder} 复用。
 */
public class IMDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(IMDecoder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定头长度 */
    public static final int FIXED_HEADER_LENGTH = 10;

    /** 最大 body 大小（4MB，防止恶意大包） */
    public static final int MAX_BODY_SIZE = 4 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        IMCommand cmd = decodeFrame(in);
        if (cmd != null) {
            out.add(cmd);
        }
    }

    /**
     * 从 ByteBuf 中解析一帧 IMCommand。
     * 如果数据不足则回退并返回 null（供 ByteToMessageDecoder 等待更多数据）。
     * 如果数据损坏则抛出 CorruptedFrameException。
     */
    public static IMCommand decodeFrame(ByteBuf in) {
        // 标记起始位置，解析失败时回退
        in.markReaderIndex();

        // ① 固定头
        if (in.readableBytes() < FIXED_HEADER_LENGTH) {
            in.resetReaderIndex();
            return null;
        }

        // ② 校验 magic
        short magic = in.readShort();
        if (magic != IMCommand.MAGIC) {
            // WebSocket 模式下由调用方处理异常，不要在这里关闭
            in.resetReaderIndex();
            throw new CorruptedFrameException("Invalid magic: 0x" + Integer.toHexString(magic & 0xFFFF));
        }

        // ③ 读固定头
        byte version = in.readByte();
        byte flags = in.readByte();
        int bodyLen = in.readInt();
        int headerLen = in.readShort() & 0xFFFF;

        // ④ 校验 bodyLen
        if (bodyLen < 0 || bodyLen > MAX_BODY_SIZE) {
            in.resetReaderIndex();
            throw new CorruptedFrameException("Invalid bodyLen: " + bodyLen);
        }
        if (headerLen < 0 || headerLen > bodyLen) {
            in.resetReaderIndex();
            throw new CorruptedFrameException("Invalid headerLen: " + headerLen);
        }

        // ⑤ 精确分帧：检查是否收齐了 bodyLen 个字节
        if (in.readableBytes() < bodyLen) {
            in.resetReaderIndex();
            return null;
        }

        // ⑥ 读 headers
        byte[] headerBytes = new byte[headerLen];
        in.readBytes(headerBytes);
        IMCommand command = parseJsonToCommand(headerBytes);
        command.setVersion(version);
        command.setFlags(flags);

        // ⑦ 读 body
        int contentLen = bodyLen - headerLen;
        if (contentLen > 0) {
            byte[] body = new byte[contentLen];
            in.readBytes(body);
            command.setBody(body);
        }

        return command;
    }

    /**
     * 从 JSON 字节解析为 IMCommand。
     */
    @SuppressWarnings("unchecked")
    static IMCommand parseJsonToCommand(byte[] data) {
        try {
            Map<String, Object> map = MAPPER.readValue(data, new TypeReference<Map<String, Object>>() {});
            return IMCommand.fromJsonMap(map);
        } catch (Exception e) {
            log.warn("Failed to parse headers JSON", e);
            return new IMCommand(); // 返回空命令，连接仍可继续
        }
    }
}

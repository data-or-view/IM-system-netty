package com.im.codec;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMEncoder + IMDecoder 配对测试。
 *
 * 使用 EmbeddedChannel 模拟 pipeline：
 *   writeOutbound → IMEncoder 编码 → ByteBuf
 *   readInbound  ← IMDecoder 解码 ← ByteBuf
 */
class IMEncoderDecoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new IMDecoder(), new IMEncoder());
    }

    @AfterEach
    void tearDown() {
        channel.close();
    }

    /** 基础：心跳消息（无 headers, 无 body） */
    @Test
    void encodeDecodeHeartbeat() {
        IMCommand original = new IMCommand(CommandType.HEARTBEAT);

        assertTrue(channel.writeOutbound(original));

        // Encoder 写入的 ByteBuf 现在可以反向喂给 Decoder
        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);
        assertTrue(buf.readableBytes() > 0);

        // 喂给 decoder
        assertTrue(channel.writeInbound(buf));

        IMCommand decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals(CommandType.HEARTBEAT, decoded.getType());
        assertEquals(original.getSeqId(), decoded.getSeqId());
        assertEquals(IMCommand.CURRENT_VERSION, decoded.getVersion());
        assertEquals(IMCommand.FLAG_REQUEST, decoded.getFlags());
        assertNotNull(decoded.getBody());
        assertEquals(0, decoded.getBody().length);
    }

    /** 单聊：headers + body */
    @Test
    void encodeDecodeChatMessage() {
        IMCommand original = new IMCommand(CommandType.SINGLE_CHAT);
        original.putHeader("fromUserId", "u001");
        original.putHeader("toUserId", "u002");
        original.putHeader("contentType", "text");
        original.setBodyString("你好，世界！");

        assertTrue(channel.writeOutbound(original));
        ByteBuf buf = channel.readOutbound();
        assertTrue(channel.writeInbound(buf));

        IMCommand decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals(CommandType.SINGLE_CHAT, decoded.getType());
        assertEquals(original.getSeqId(), decoded.getSeqId());
        assertEquals("u001", decoded.getHeader("fromUserId"));
        assertEquals("u002", decoded.getHeader("toUserId"));
        assertEquals("text", decoded.getHeader("contentType"));
        assertEquals("你好，世界！", decoded.getBodyString());
    }

    /** ACK 响应：flags = FLAG_RESPONSE */
    @Test
    void encodeDecodeAck() {
        IMCommand request = new IMCommand(CommandType.LOGIN);
        IMCommand ack = request.createAcknowledgement(CommandType.LOGIN_ACK);

        assertTrue(channel.writeOutbound(ack));
        ByteBuf buf = channel.readOutbound();
        assertTrue(channel.writeInbound(buf));

        IMCommand decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals(CommandType.LOGIN_ACK, decoded.getType());
        assertEquals(request.getSeqId(), decoded.getSeqId());
        assertTrue(decoded.isResponse());
    }

    /** 多消息连续发送（验证不会粘包） */
    @Test
    void multipleMessagesNoMerge() {
        EmbeddedChannel encChannel = new EmbeddedChannel(new IMEncoder());

        IMCommand msg1 = new IMCommand(CommandType.HEARTBEAT);
        IMCommand msg2 = new IMCommand(CommandType.SINGLE_CHAT);
        msg2.putHeader("t", "1");
        msg2.setBodyString("hello");
        IMCommand msg3 = new IMCommand(CommandType.LOGIN);
        msg3.putHeader("u", "admin");
        msg3.setBodyString("pwd");

        // 全部编码到同一个 ByteBuf
        assertTrue(encChannel.writeOutbound(msg1));
        assertTrue(encChannel.writeOutbound(msg2));
        assertTrue(encChannel.writeOutbound(msg3));

        ByteBuf combined = Unpooled.buffer();
        ByteBuf buf;
        while ((buf = encChannel.readOutbound()) != null) {
            combined.writeBytes(buf);
            buf.release();
        }

        // 用另一个 EmbeddedChannel 解码（多次 channelRead 自动累积）
        EmbeddedChannel decChannel = new EmbeddedChannel(new IMDecoder());
        assertTrue(decChannel.writeInbound(combined));

        IMCommand d1 = decChannel.readInbound();
        assertNotNull(d1);
        assertEquals(CommandType.HEARTBEAT, d1.getType());

        IMCommand d2 = decChannel.readInbound();
        assertNotNull(d2);
        assertEquals(CommandType.SINGLE_CHAT, d2.getType());
        assertEquals("hello", d2.getBodyString());

        IMCommand d3 = decChannel.readInbound();
        assertNotNull(d3);
        assertEquals(CommandType.LOGIN, d3.getType());
        assertEquals("admin", d3.getHeader("u"));
        assertEquals("pwd", d3.getBodyString());

        assertNull(decChannel.readInbound()); // 没有更多消息
        encChannel.close();
        decChannel.close();
    }

    /** 魔术字错误 */
    @Test
    void invalidMagicThrowsException() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(0xBEEF); // 错误的 magic
        buf.writeByte(1);
        buf.writeByte(0);
        buf.writeInt(0);
        buf.writeShort(0);

        IMDecoder decoder = new IMDecoder();
        List<Object> out = new java.util.ArrayList<>();

        assertThrows(Exception.class, () -> {
            decoder.decode(null, buf, out);
        });
    }

    /** bodyLen 过大（超过 4MB） */
    @Test
    void oversizedBodyRejected() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(IMCommand.MAGIC);
        buf.writeByte(1);
        buf.writeByte(0);
        buf.writeInt(5 * 1024 * 1024); // bodyLen = 5MB > 4MB limit
        buf.writeShort(0);

        IMDecoder decoder = new IMDecoder();
        List<Object> out = new java.util.ArrayList<>();

        assertThrows(Exception.class, () -> {
            decoder.decode(null, buf, out);
        });
    }

    /** headerLen > bodyLen */
    @Test
    void invalidHeaderLenRejected() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(IMCommand.MAGIC);
        buf.writeByte(1);
        buf.writeByte(0);
        buf.writeInt(100);  // bodyLen = 100
        buf.writeShort(200); // headerLen = 200 > 100

        IMDecoder decoder = new IMDecoder();
        List<Object> out = new java.util.ArrayList<>();

        assertThrows(Exception.class, () -> {
            decoder.decode(null, buf, out);
        });
    }

    /** headerLen = 0 (无 headers) + bodyLen = 0 (无 body) */
    @Test
    void zeroLengthsOk() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(IMCommand.MAGIC);
        buf.writeByte(1);
        buf.writeByte(0);
        buf.writeInt(0);  // bodyLen = 0
        buf.writeShort(0); // headerLen = 0

        IMDecoder decoder = new IMDecoder();
        List<Object> out = new java.util.ArrayList<>();

        decoder.decode(null, buf, out);

        assertEquals(1, out.size());
        IMCommand cmd = (IMCommand) out.get(0);
        assertNotNull(cmd.getBody());
        assertEquals(0, cmd.getBody().length);
    }

    /** 不完整的帧（数据不足） */
    @Test
    void incompleteFrame() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(IMCommand.MAGIC);
        buf.writeByte(1);
        buf.writeByte(0);
        buf.writeInt(100); // bodyLen = 100
        buf.writeShort(10);
        // 只写了 headerLen=10 而没写 headers 和 body
        buf.writeBytes(new byte[5]); // 总共只有 15 字节，不够 bodyLen

        IMDecoder decoder = new IMDecoder();
        List<Object> out = new java.util.ArrayList<>();
        decoder.decode(null, buf, out);

        assertEquals(0, out.size(), "Should not decode incomplete frame");
    }
}

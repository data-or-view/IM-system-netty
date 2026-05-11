package com.im.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMCommand 序列化与基础功能测试。
 */
class IMCommandTest {

    @Test
    void defaultConstructor() {
        IMCommand cmd = new IMCommand();
        assertEquals(IMCommand.CURRENT_VERSION, cmd.getVersion());
        assertEquals(IMCommand.FLAG_REQUEST, cmd.getFlags());
        assertFalse(cmd.isResponse());
        assertFalse(cmd.isOneway());
        assertTrue(cmd.getSeqId() > 0);
        assertTrue(cmd.getTimestamp() > 0);
        assertNotNull(cmd.getHeaders());
        assertNotNull(cmd.getBody());
        assertEquals(0, cmd.getBody().length);
    }

    @Test
    void typedConstructor() {
        IMCommand cmd = new IMCommand(CommandType.HEARTBEAT);
        assertEquals(CommandType.HEARTBEAT, cmd.getType());
    }

    @Test
    void headerPutAndGet() {
        IMCommand cmd = new IMCommand();
        cmd.putHeader("fromUserId", "u001");
        cmd.putHeader("toUserId", "u002");
        assertEquals("u001", cmd.getHeader("fromUserId"));
        assertEquals("u002", cmd.getHeader("toUserId"));
        assertNull(cmd.getHeader("nonExistent"));
    }

    @Test
    void bodyStringRoundTrip() {
        IMCommand cmd = new IMCommand();
        cmd.setBodyString("你好，世界");
        assertEquals("你好，世界", cmd.getBodyString());
    }

    @Test
    void bodyNullSetAsEmpty() {
        IMCommand cmd = new IMCommand();
        cmd.setBodyString(null);
        assertEquals(0, cmd.getBody().length);
        assertEquals("", cmd.getBodyString());
    }

    @Test
    void createAcknowledgementInheritsFields() {
        IMCommand request = new IMCommand(CommandType.LOGIN);
        int originalSeq = request.getSeqId();
        String originalMsgId = request.getMessageId();

        IMCommand ack = request.createAcknowledgement(CommandType.LOGIN_ACK);
        assertEquals(CommandType.LOGIN_ACK, ack.getType());
        assertEquals(originalSeq, ack.getSeqId());
        assertEquals(originalMsgId, ack.getMessageId());
        assertTrue(ack.isResponse());
    }

    @Test
    void toJsonMapContainsProtocolFields() {
        IMCommand cmd = new IMCommand(CommandType.SINGLE_CHAT);
        cmd.putHeader("fromUserId", "u1");

        Map<String, Object> map = cmd.toJsonMap();
        assertEquals((int) CommandType.SINGLE_CHAT.getCode(), map.get("_op"));
        assertEquals(cmd.getSeqId(), map.get("_seq"));
        assertEquals(cmd.getMessageId(), map.get("_mid"));
        assertEquals(cmd.getTimestamp(), ((Number) map.get("_ts")).longValue());
        assertEquals((int) IMCommand.CURRENT_VERSION, map.get("_ver"));
        assertEquals((int) IMCommand.FLAG_REQUEST, map.get("_flg"));
        assertEquals("u1", map.get("fromUserId"));
    }

    @Test
    void fromJsonMapRoundTrip() {
        IMCommand original = new IMCommand(CommandType.SINGLE_CHAT);
        original.putHeader("fromUserId", "u1");
        original.putHeader("contentType", "text");
        original.setMessageId("msg-001");

        Map<String, Object> map = original.toJsonMap();
        IMCommand restored = IMCommand.fromJsonMap(map);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getSeqId(), restored.getSeqId());
        assertEquals(original.getMessageId(), restored.getMessageId());
        assertEquals(original.getTimestamp(), restored.getTimestamp());
        assertEquals(original.getVersion(), restored.getVersion());
        assertEquals(original.getFlags(), restored.getFlags());
        assertEquals("u1", restored.getHeader("fromUserId"));
        assertEquals("text", restored.getHeader("contentType"));
    }

    @Test
    void fromJsonMapEmptyMap() {
        IMCommand cmd = IMCommand.fromJsonMap(Map.of());
        assertNull(cmd.getType());
        assertTrue(cmd.getHeaders().isEmpty());
    }

    @Test
    void fromJsonMapStripsProtocolFieldsFromHeaders() {
        Map<String, Object> map = Map.of(
                "_op", 10,
                "fromUserId", "u1"
        );
        IMCommand cmd = IMCommand.fromJsonMap(map);
        assertEquals("u1", cmd.getHeader("fromUserId"));
        assertNull(cmd.getHeader("_op")); // stripped
    }

    @Test
    void magicConstant() {
        assertEquals((short) 0xACAC, IMCommand.MAGIC);
    }

    @Test
    void flagsConstants() {
        assertEquals(0, IMCommand.FLAG_REQUEST);
        assertEquals(1, IMCommand.FLAG_RESPONSE);
        assertEquals(2, IMCommand.FLAG_ONEWAY);
    }

    @Test
    void seqIdAutoIncrements() {
        IMCommand a = new IMCommand();
        IMCommand b = new IMCommand();
        assertTrue(b.getSeqId() > a.getSeqId(), "seqId should auto-increment");
    }

    @Test
    void equalsByTypeAndSeqId() {
        IMCommand a = new IMCommand(CommandType.HEARTBEAT);
        IMCommand b = new IMCommand(CommandType.HEARTBEAT);
        b.setSeqId(a.getSeqId());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}

package com.im.core.revoke;

import com.im.api.IMCommand;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.api.IMessageRevoke;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地消息撤回管理器（单机开发/测试用）。
 *
 * 基于 ConcurrentHashMap 记录撤回的消息 seq。
 * 生产环境请换 DB 实现（需要持久化撤回记录）。
 */
public class LocalMessageRevoke implements IMessageRevoke {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageRevoke.class);

    /** 默认撤回时间窗口（2 分钟） */
    public static final long DEFAULT_REVOKE_WINDOW_MS = 120_000;

    /** 已撤回的消息 seq（用于接收端过滤） */
    private final ConcurrentMap<String, Long> revokedSeqs = new ConcurrentHashMap<>();

    private long revokeWindowMs = DEFAULT_REVOKE_WINDOW_MS;

    public LocalMessageRevoke() {}

    public LocalMessageRevoke(long revokeWindowMs) {
        this.revokeWindowMs = revokeWindowMs;
    }

    @Override
    public RevokeInfo revokeMessage(String userId, String messageId, int seq, String groupId) {
        long now = System.currentTimeMillis();
        // 在实际实现中需要从消息存储里加载消息时间戳来校验窗口
        // 这里是占位实现，只记录撤回
        revokedSeqs.put(messageId, now);
        log.info("Message revoked: userId={}, messageId={}, seq={}, groupId={}",
                userId, messageId, seq, groupId);
        return new RevokeInfo(messageId, seq, userId, now);
    }

    @Override
    public boolean canRevoke(String userId, IMCommand msg, String groupId) {
        // 占位：允许撤回
        return true;
    }

    /** 检查消息是否已被撤回（供 PullMessageHandler 过滤） */
    public boolean isRevoked(String messageId) {
        return revokedSeqs.containsKey(messageId);
    }
}

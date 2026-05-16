package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IConnectionSession;
import com.im.api.ISessionManager;
import com.im.api.RequestHandler;
import com.im.core.usecase.HeartbeatUseCase;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 心跳 handler（仅 WS）。
 *
 * <p>需要访问 Netty Channel 进行 session 活跃度更新。</p>
 */
public class HeartbeatHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final HeartbeatUseCase heartbeatUseCase;
    private final ISessionManager sessionManager;

    public HeartbeatHandler(HeartbeatUseCase heartbeatUseCase, ISessionManager sessionManager) {
        this.heartbeatUseCase = heartbeatUseCase;
        this.sessionManager = sessionManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        Channel channel = req.attribute("_channel");
        if (channel != null) {
            IConnectionSession session = sessionManager.getByChannel(channel);
            if (session != null) {
                session.touch();
                if (session.isAuthenticated()) {
                    heartbeatUseCase.execute(session.getUserId(), session.getPlatformId());
                }
            }
        }

        return Map.of("status", "OK", "timestamp", System.currentTimeMillis());
    }
}

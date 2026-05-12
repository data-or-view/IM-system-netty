package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IConnectionSession;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 心跳处理器，实现 IMessageHandler 接口。
 *
 * 流程：
 *   ① 刷新 session.touch() — 本地超时检测
 *   ② 续期在线状态（IRouteTable.renewOnline）— Redis 保活
 *   ③ 回复 HEARTBEAT_ACK
 */
public class HeartbeatHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;

    public HeartbeatHandler(ISessionManager sessionManager) {
        this(sessionManager, null);
    }

    public HeartbeatHandler(ISessionManager sessionManager, IRouteTable routeTable) {
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        // ① 刷新会话活跃时间
        IConnectionSession session = sessionManager.getByChannel(ctx.channel());
        if (session != null) {
            session.touch();

            // ② 续期 Redis 在线状态
            if (routeTable != null && session.isAuthenticated()) {
                routeTable.renewOnline(session.getUserId(), session.getPlatformId());
            }
        }

        // ③ 回复 HEARTBEAT_ACK
        IMCommand ack = msg.createAcknowledgement(CommandType.HEARTBEAT_ACK);
        ctx.writeAndFlush(ack);
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.HEARTBEAT);
    }
}

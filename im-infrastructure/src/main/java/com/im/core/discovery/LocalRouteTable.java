package com.im.core.discovery;

import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.PlatformID;
import com.im.api.RouteNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 单机路由表（开发/测试用）。
 *
 * 集群中只有一个节点，所有用户都在本节点。
 * 路由查询直接委托给 SessionManager：
 *   · SessionManager 中有该用户 → 返回 local 路由
 *   · SessionManager 中无该用户 → 返回 null（离线）
 *
 * 对应 OpenIM 单机模式：所有用户连接都在唯一一个 MsgGateway 上。
 */
public class LocalRouteTable implements IRouteTable {

    private static final Logger log = LoggerFactory.getLogger(LocalRouteTable.class);

    private final ISessionManager sessionManager;
    private final String localNodeId;

    public LocalRouteTable(ISessionManager sessionManager, String localNodeId) {
        this.sessionManager = sessionManager;
        this.localNodeId = localNodeId;
        log.info("LocalRouteTable created: nodeId={}", localNodeId);
    }

    @Override
    public void online(String userId, String nodeId) {
        if (!localNodeId.equals(nodeId)) {
            log.warn("LocalRouteTable: ignoring remote routing userId={}, nodeId={}", userId, nodeId);
            return;
        }
        // SessionManager.bindUser 已处理绑定
        log.info("Route online: userId={}, node={}", userId, localNodeId);
    }

    @Override
    public void offline(String userId, String nodeId) {
        if (!localNodeId.equals(nodeId)) {
            log.warn("LocalRouteTable: ignoring remote unroute userId={}, nodeId={}", userId, nodeId);
            return;
        }
        log.info("Route offline: userId={}, node={}", userId, localNodeId);
    }

    @Override
    public RouteNode lookup(String userId) {
        boolean online = sessionManager.getByUserId(userId) != null;
        if (online) {
            return RouteNode.local(localNodeId);
        }
        return null;
    }

    @Override
    public List<RouteNode> lookupAll(String userId) {
        RouteNode rn = lookup(userId);
        if (rn != null) {
            return List.of(rn);
        }
        return Collections.emptyList();
    }

    // ========== 在线状态（单机模式 = 查 SessionManager） ==========

    @Override
    public void setOnline(String userId, int platformId) {
        log.info("Online status set: userId={}, platform={}", userId, platformId);
    }

    @Override
    public void setOffline(String userId, int platformId) {
        log.info("Online status removed: userId={}, platform={}", userId, platformId);
    }

    @Override
    public List<Integer> getOnlinePlatforms(String userId) {
        boolean online = sessionManager.getByUserId(userId) != null;
        return online ? List.of(PlatformID.DEFAULT) : Collections.emptyList();
    }

    @Override
    public void renewOnline(String userId, int platformId) {
        // 心跳续期，单机模式下无需操作
    }
}

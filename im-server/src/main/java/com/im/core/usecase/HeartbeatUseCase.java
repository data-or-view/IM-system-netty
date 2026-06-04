package com.im.core.usecase;

import com.im.api.IRouteTable;

public class HeartbeatUseCase {

    private final IRouteTable routeTable;

    public HeartbeatUseCase(IRouteTable routeTable) {
        this.routeTable = routeTable;
    }

    public void execute(String userId, int platformId) {
        execute(userId, platformId, "default");
    }

    public void execute(String userId, int platformId, String sessionId) {
        if (routeTable != null && userId != null) {
            routeTable.renewOnline(userId, platformId, sessionId);
        }
    }
}

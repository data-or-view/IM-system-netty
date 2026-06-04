package com.im.core.usecase;

import com.im.api.IRouteTable;
import com.im.api.PlatformID;
import com.im.api.RouteNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeartbeatUseCaseTest {

    @Test
    void renewsOnlineRouteForCurrentSession() {
        RecordingRouteTable routeTable = new RecordingRouteTable();
        HeartbeatUseCase useCase = new HeartbeatUseCase(routeTable);

        useCase.execute("u1", PlatformID.IOS, "session-1");

        assertEquals("u1|" + PlatformID.IOS + "|session-1", routeTable.lastRenewal);
    }

    private static final class RecordingRouteTable implements IRouteTable {
        private String lastRenewal;

        @Override
        public void online(String userId, String nodeId, int platformId, String sessionId) {
        }

        @Override
        public void offline(String userId, String nodeId, int platformId, String sessionId) {
        }

        @Override
        public RouteNode lookup(String userId) {
            return null;
        }

        @Override
        public List<RouteNode> lookupAll(String userId) {
            return List.of();
        }

        @Override
        public void setOnline(String userId, int platformId) {
        }

        @Override
        public void setOffline(String userId, int platformId) {
        }

        @Override
        public List<Integer> getOnlinePlatforms(String userId) {
            return List.of();
        }

        @Override
        public void renewOnline(String userId, int platformId) {
            lastRenewal = userId + "|" + platformId + "|default";
        }

        @Override
        public void renewOnline(String userId, int platformId, String sessionId) {
            lastRenewal = userId + "|" + platformId + "|" + sessionId;
        }
    }
}

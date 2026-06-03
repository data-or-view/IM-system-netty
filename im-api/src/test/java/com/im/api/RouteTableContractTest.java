package com.im.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteTableContractTest {

    @Test
    void defaultOnlineUsesDefaultPlatformAndSessionRouteKey() {
        RecordingRouteTable table = new RecordingRouteTable();

        table.online("user001", "node-a");

        assertEquals(List.of("user001|node-a|5|default"), table.onlineCalls);
    }

    @Test
    void defaultOfflineUsesDefaultPlatformAndSessionRouteKey() {
        RecordingRouteTable table = new RecordingRouteTable();

        table.offline("user001", "node-a");

        assertEquals(List.of("user001|node-a|5|default"), table.offlineCalls);
    }

    private static final class RecordingRouteTable implements IRouteTable {
        private final List<String> onlineCalls = new ArrayList<>();
        private final List<String> offlineCalls = new ArrayList<>();
        private final Map<String, List<Integer>> onlinePlatforms = new HashMap<>();

        @Override
        public void online(String userId, String nodeId, int platformId, String sessionId) {
            onlineCalls.add(userId + "|" + nodeId + "|" + platformId + "|" + sessionId);
        }

        @Override
        public void offline(String userId, String nodeId, int platformId, String sessionId) {
            offlineCalls.add(userId + "|" + nodeId + "|" + platformId + "|" + sessionId);
        }

        @Override
        public RouteNode lookup(String userId) {
            return null;
        }

        @Override
        public List<RouteNode> lookupAll(String userId) {
            return List.of();
        }

        
        public List<RouteBinding> lookupAllBindings(String userId) {
            return List.of();
        }

        @Override
        public void setOnline(String userId, int platformId) {
            onlinePlatforms.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(platformId);
        }

        @Override
        public void setOffline(String userId, int platformId) {
            onlinePlatforms.computeIfAbsent(userId, ignored -> new ArrayList<>()).remove(Integer.valueOf(platformId));
        }

        @Override
        public List<Integer> getOnlinePlatforms(String userId) {
            return onlinePlatforms.getOrDefault(userId, List.of());
        }

        @Override
        public void renewOnline(String userId, int platformId) {
            setOnline(userId, platformId);
        }
    }
}

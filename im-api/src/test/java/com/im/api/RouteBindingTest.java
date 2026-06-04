package com.im.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteBindingTest {

    @Test
    void expireAtZeroMeansNoExpiryForLegacyBindings() {
        RouteBinding binding = new RouteBinding("u1", "node-a", PlatformID.IOS, "s1", 0);

        assertFalse(binding.isExpired(System.currentTimeMillis()));
    }

    @Test
    void detectsExpiredRouteBinding() {
        long now = System.currentTimeMillis();
        RouteBinding binding = new RouteBinding("u1", "node-a", PlatformID.IOS, "s1", now - 1);

        assertTrue(binding.isExpired(now));
    }

    @Test
    void remoteBindingCreatesRemoteRouteNode() {
        RouteBinding binding = new RouteBinding("u1", "node-b", PlatformID.IOS, "s1", 0);

        assertTrue(binding.toRouteNode("node-a").isRemote());
    }
}

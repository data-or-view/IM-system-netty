package com.im.bootstrap;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    @Test
    void ignoresProxyHeaderUnlessTrustedProxyIsEnabled() {
        DefaultFullHttpRequest request = requestWithForwardedFor("203.0.113.9, 10.0.0.12");
        InetSocketAddress remoteAddress = new InetSocketAddress("192.0.2.10", 58000);

        String clientIp = ClientIpResolver.fromHttpRequest(
                request, remoteAddress, false, ClientIpResolver.DEFAULT_PROXY_HEADER);

        assertEquals("192.0.2.10", clientIp);
    }

    @Test
    void trustedProxyUsesFirstForwardedAddress() {
        DefaultFullHttpRequest request = requestWithForwardedFor("203.0.113.9, 10.0.0.12");
        InetSocketAddress remoteAddress = new InetSocketAddress("192.0.2.10", 58000);

        String clientIp = ClientIpResolver.fromHttpRequest(
                request, remoteAddress, true, ClientIpResolver.DEFAULT_PROXY_HEADER);

        assertEquals("203.0.113.9", clientIp);
    }

    private static DefaultFullHttpRequest requestWithForwardedFor(String value) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/api/user/info");
        request.headers().set(ClientIpResolver.DEFAULT_PROXY_HEADER, value);
        return request;
    }
}

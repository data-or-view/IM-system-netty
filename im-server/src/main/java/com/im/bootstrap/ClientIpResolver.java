package com.im.bootstrap;

import io.netty.handler.codec.http.FullHttpRequest;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Resolves the client identity used by cross-node controls such as rate limiting.
 *
 * <p>Proxy headers are only trusted when explicitly enabled by configuration.
 * Without that switch, the socket remote address is the source of truth.</p>
 */
public final class ClientIpResolver {

    public static final String DEFAULT_PROXY_HEADER = "X-Forwarded-For";
    public static final String UNKNOWN_CLIENT_IP = "unknown";

    private ClientIpResolver() {
    }

    public static String fromHttpRequest(FullHttpRequest request,
                                         SocketAddress remoteAddress,
                                         boolean trustedProxyEnabled,
                                         String clientIpHeader) {
        if (trustedProxyEnabled) {
            String headerName = isBlank(clientIpHeader) ? DEFAULT_PROXY_HEADER : clientIpHeader;
            String headerValue = request.headers().get(headerName);
            String trustedHeaderIp = firstForwardedIp(headerValue);
            if (!isBlank(trustedHeaderIp)) {
                return trustedHeaderIp;
            }
        }
        return fromRemoteAddress(remoteAddress);
    }

    public static String fromRemoteAddress(SocketAddress remoteAddress) {
        if (remoteAddress instanceof InetSocketAddress inet) {
            if (inet.getAddress() != null) {
                return inet.getAddress().getHostAddress();
            }
            if (!isBlank(inet.getHostString())) {
                return inet.getHostString();
            }
        }
        String raw = String.valueOf(remoteAddress);
        return isBlank(raw) || "null".equals(raw) ? UNKNOWN_CLIENT_IP : raw;
    }

    private static String firstForwardedIp(String headerValue) {
        if (isBlank(headerValue)) {
            return null;
        }
        for (String part : headerValue.split(",")) {
            String candidate = part.trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

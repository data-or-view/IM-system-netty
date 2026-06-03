package com.im.core.session;

import com.im.api.ConnectionRef;
import io.netty.channel.Channel;

import java.util.Objects;

/**
 * Adapts a Netty Channel to the transport-neutral ConnectionRef API.
 */
public final class NettyConnectionRef implements ConnectionRef {

    private final Channel channel;
    private final String connectionId;

    public NettyConnectionRef(Channel channel) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.connectionId = "netty:" + System.identityHashCode(channel);
    }

    public Channel channel() {
        return channel;
    }

    @Override
    public String connectionId() {
        return connectionId;
    }

    @Override
    public String remoteAddress() {
        return String.valueOf(channel.remoteAddress());
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public void write(Object message) {
        channel.writeAndFlush(message);
    }

    @Override
    public void close() {
        channel.close();
    }

    public static String connectionId(Channel channel) {
        return "netty:" + System.identityHashCode(channel);
    }
}

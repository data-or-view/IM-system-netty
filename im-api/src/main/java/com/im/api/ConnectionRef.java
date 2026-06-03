package com.im.api;

/**
 * Transport-neutral connection reference.
 *
 * <p>The API layer should not know whether the underlying connection is Netty,
 * TCP, gRPC, or another gateway. Implementations adapt transport-specific
 * handles to this small contract.</p>
 */
public interface ConnectionRef {

    String connectionId();

    String remoteAddress();

    boolean isActive();

    void write(Object message);

    void close();
}

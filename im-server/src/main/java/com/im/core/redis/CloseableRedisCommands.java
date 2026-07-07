package com.im.core.redis;

/**
 * 可关闭的 Redis 同步命令封装，管理底层连接生命周期。
 */
public final class CloseableRedisCommands implements AutoCloseable {
    private final AutoCloseable connection;
    private final Object sync;

    CloseableRedisCommands(AutoCloseable connection, Object sync) {
        this.connection = connection;
        this.sync = sync;
    }

    /** 获取同步命令接口，调用方按需转型。 */
    @SuppressWarnings("unchecked")
    public <T> T sync() {
        return (T) sync;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}

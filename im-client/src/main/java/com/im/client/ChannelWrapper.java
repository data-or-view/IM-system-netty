package com.im.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Channel 包装器，参考 RocketMQ NettyRemotingClient.ChannelWrapper。
 *
 * 核心职责：
 *   ① 持有 ChannelFuture，提供 isOK() 检查连接状态
 *   ② 断线时调用 reconnect() 异步重建连接
 *   ③ tryClose() 判断是否为当前 channel（避免关闭新连接的误伤）
 *
 * 为什么需要这层封装：
 *   RocketMQ 的连接是「懒 + 异步」的 —— 不主动维护连接，而是在需要时
 *   getAndCreateChannel() 检查已有连接是否可用，不可用就重建。
 *   IM 客户端也使用同样的模式。
 *
 * 线程安全：ReentrantReadWriteLock 保护 channelFuture 的替换。
 */
public class ChannelWrapper {

    private static final Logger log = LoggerFactory.getLogger(ChannelWrapper.class);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final IMClient client;
    private final String address;
    private volatile ChannelFuture channelFuture;

    public ChannelWrapper(IMClient client, String address, ChannelFuture channelFuture) {
        this.client = client;
        this.address = address;
        this.channelFuture = channelFuture;
    }

    /**
     * 连接是否就绪。
     * ChannelFuture 已完成 + Channel 已激活。
     */
    public boolean isOK() {
        Channel channel = getChannel();
        return channel != null && channel.isActive();
    }

    /**
     * 获取当前 Channel，可能为 null（未连接或连接中）。
     */
    public Channel getChannel() {
        ChannelFuture cf = getChannelFuture();
        return cf != null && cf.isDone() ? cf.channel() : null;
    }

    /**
     * 获取 ChannelFuture。
     */
    public ChannelFuture getChannelFuture() {
        lock.readLock().lock();
        try {
            return this.channelFuture;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 判断给定的 channel 是否就是当前包装的这个。
     */
    public boolean isWrapperOf(Channel channel) {
        Channel cur = getChannel();
        return cur != null && cur == channel;
    }

    /**
     * 懒重连。当检测到当前 channel 断开时调用。
     * 创建新的 ChannelFuture 替换旧的，旧的放入 channelToClose 等待关闭。
     */
    public boolean reconnect(Channel channel) {
        if (!isWrapperOf(channel)) {
            log.warn("ChannelWrapper reconnect skipped: current channel is different");
            return false;
        }

        if (lock.writeLock().tryLock()) {
            try {
                if (isWrapperOf(channel)) {
                    ChannelFuture newFuture = client.doConnect();
                    this.channelFuture = newFuture;
                    log.info("ChannelWrapper reconnected to {}, new channelId={}",
                            address, newFuture.channel().id());
                    return true;
                }
            } catch (Throwable t) {
                log.error("ChannelWrapper reconnect error", t);
            } finally {
                lock.writeLock().unlock();
            }
        }
        return false;
    }

    /**
     * 判断是否可以关闭给定的 channel。
     * 只有「当前 wrapper 的 channel == 入参 channel」时才返回 true。
     * 防止误关新重建的 channel。
     */
    public boolean tryClose(Channel channel) {
        lock.readLock().lock();
        try {
            Channel cur = getChannel();
            return cur != null && cur.equals(channel);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void close() {
        ChannelFuture cf;
        lock.readLock().lock();
        try {
            cf = this.channelFuture;
        } finally {
            lock.readLock().unlock();
        }
        if (cf != null && cf.channel() != null) {
            cf.channel().close();
        }
    }

    public String getAddress() {
        return address;
    }

    @Override
    public String toString() {
        Channel ch = getChannel();
        return "ChannelWrapper{" + "addr=" + address
                + ", active=" + (ch != null && ch.isActive())
                + ", channelId=" + (ch != null ? ch.id() : "null") + '}';
    }
}

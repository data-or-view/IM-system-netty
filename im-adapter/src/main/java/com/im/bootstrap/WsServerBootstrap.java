package com.im.bootstrap;

import com.im.bootstrap.ws.JsonWsCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 协议的 Netty ServerBootstrap。
 *
 * <p>Pipeline: HttpServerCodec → HttpObjectAggregator → WebSocketServerProtocolHandler
 * → JsonWsCodec → connectionEventHandler → routerHandler</p>
 *
 * <p>客户端通过 WebSocket 发送 JSON 文本帧与 IM 服务端通信。</p>
 */
public class WsServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(WsServerBootstrap.class);

    private WsServerBootstrap() {}

    /**
     * 启动 WebSocket 服务器。
     *
     * @param bossGroup              acceptor 线程组
     * @param workerGroup            IO 线程组
     * @param port                   绑定端口
     * @param useEpoll               是否使用 epoll
     * @param connectionEventHandler 连接事件处理器
     * @param routerHandler          消息路由分发器
     * @return 绑定后的 Channel
     */
    public static Channel start(EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                                int port, boolean useEpoll,
                                ChannelHandler connectionEventHandler,
                                ChannelHandler routerHandler) throws InterruptedException {
        ServerBootstrap wsBootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(new WebSocketServerProtocolHandler(
                                "/ws", null, true, 65536));
                        p.addLast(new JsonWsCodec());
                        p.addLast(connectionEventHandler);
                        p.addLast(routerHandler);
                    }
                });

        Channel channel = wsBootstrap.bind(port).sync().channel();
        log.info("WebSocket server started: port={}, path=/ws", port);
        return channel;
    }
}

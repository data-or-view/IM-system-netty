package com.im.bootstrap;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP REST 协议的 Netty ServerBootstrap。
 *
 * <p>Pipeline: HttpServerCodec → HttpObjectAggregator → HttpRequestAdapter</p>
 *
 * <p>与 WS 共用同一 ApiDispatcher，HTTP 请求经过 HttpRequestAdapter 转为 ApiRequest。
 * 提供 RESTful API 供管理后台和第三方系统调用。</p>
 */
public class HttpServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(HttpServerBootstrap.class);
    private static final int MAX_HTTP_CONTENT_LENGTH = 100 * 1024 * 1024;

    private HttpServerBootstrap() {}

    /**
     * 启动 HTTP REST 服务器。
     *
     * @param bossGroup    acceptor 线程组
     * @param workerGroup  IO 线程组
     * @param port         绑定端口
     * @param useEpoll     是否使用 epoll
     * @param httpHandler  HTTP REST 请求处理器（{@link com.im.bootstrap.http.HttpRestHandler}）
     * @return 绑定后的 Channel
     */
    public static Channel start(EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                                int port, boolean useEpoll,
                                String corsAllowedOrigins,
                                ChannelHandler httpHandler) throws InterruptedException {
        com.im.bootstrap.http.CorsConfig.configure(corsAllowedOrigins);
        ServerBootstrap httpBootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
                        p.addLast(httpHandler);
                    }
                });

        Channel channel = httpBootstrap.bind(port).sync().channel();
        log.info("HTTP REST server started: port={}", port);
        return channel;
    }
}

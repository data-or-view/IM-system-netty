package com.im.bootstrap;

import com.im.bootstrap.ws.MessageEncoder;
import com.im.bootstrap.ws.WsRequestAdapter;
import com.im.core.dispatcher.ApiDispatcher;
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

import java.util.concurrent.ExecutorService;

/**
 * WebSocket 协议的 Netty ServerBootstrap。
 *
 * <p>Pipeline 顺序有要求：HttpServerCodec → HttpObjectAggregator → WebSocketServerProtocolHandler
 * （完成 HTTP 升级到 WS）→ connectionEventHandler（管理连接生命周期）
 * → MessageEncoder（出站 Message 编码）→ WsRequestAdapter（入站 JSON 帧转 ApiRequest）。</p>
 *
 * <p>MessageEncoder 放在 connectionEventHandler 之后、WsRequestAdapter 之前，
 * 如此出站消息从 pipeline 尾部写入时能经过 encoder；若放在更前，出站 Message 会绕过 encoder。</p>
 *
 * <p>WsRequestAdapter 替代旧的 JsonWsCodec + MessageRouterHandler 组合：
 * 解析 JSON 帧为 {@link com.im.api.ApiRequest} 后提交到虚拟线程池由 {@link ApiDispatcher} 处理。</p>
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
     * @param dispatcher             统一请求调度器
     * @param virtualExecutor        虚拟线程执行器
     * @param requestAdmission       请求准入器
     * @return 绑定后的 Channel
     */
    public static Channel start(EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                                int port, boolean useEpoll,
                                ChannelHandler connectionEventHandler,
                                ApiDispatcher dispatcher,
                                ExecutorService virtualExecutor,
                                RequestAdmission requestAdmission) throws InterruptedException {
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
                        p.addLast(connectionEventHandler);
                        p.addLast(new MessageEncoder());
                        p.addLast(new WsRequestAdapter(dispatcher, virtualExecutor, requestAdmission));
                    }
                });

        Channel channel = wsBootstrap.bind(port).sync().channel();
        log.info("WebSocket server started: port={}, path=/ws", port);
        return channel;
    }
}

package com.im.bootstrap.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP 请求日志拦截器。
 *
 * <p>在 preHandle 记录入站请求，在 afterComplete 记录完成状态或异常。
 * 放在拦截器链末尾（order = {@link Integer#MAX_VALUE}），不阻断任何请求。</p>
 */
public class HttpRequestLogInterceptor implements HttpInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestLogInterceptor.class);

    @Override
    public String name() {
        return "requestLog";
    }

    /** 放在拦截器链末尾。 */
    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean preHandle(FullHttpRequest req, ChannelHandlerContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("HTTP {} {}", req.method().name(), req.uri());
        }
        return true;
    }

    @Override
    public void afterComplete(FullHttpRequest req, ChannelHandlerContext ctx, Object result, Exception ex) {
        if (ex != null) {
            log.warn("HTTP {} {} failed: {}", req.method().name(), req.uri(), ex.getMessage());
        } else if (log.isDebugEnabled()) {
            log.debug("HTTP {} {} completed", req.method().name(), req.uri());
        }
    }
}

package com.im.bootstrap.http;

import com.im.api.ResponseWriter;
import com.im.common.enums.ImErrorCode;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP REST 协议响应写回。
 *
 * <p>将 handler 返回的结果直接写为 HTTP JSON 响应。</p>
 */
public class HttpResponseWriter implements ResponseWriter {

    private final ChannelHandlerContext ctx;
    private final AtomicBoolean committed = new AtomicBoolean(false);

    public HttpResponseWriter(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void write(Object result) {
        if (result != null && committed.compareAndSet(false, true)) {
            JsonResponse.ok(ctx, result);
        }
    }

    @Override
    public void writeError(ImErrorCode code, String detail) {
        if (committed.compareAndSet(false, true)) {
            JsonResponse.imError(ctx, code, detail);
        }
    }

    @Override
    public boolean isCommitted() {
        return committed.get();
    }
}

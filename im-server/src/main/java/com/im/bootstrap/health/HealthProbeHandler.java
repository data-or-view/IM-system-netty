package com.im.bootstrap.health;

import com.im.bootstrap.RequestAdmission;
import com.im.bootstrap.http.JsonResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

public final class HealthProbeHandler {

    private final String nodeId;
    private final RequestAdmission requestAdmission;

    public HealthProbeHandler(String nodeId, RequestAdmission requestAdmission) {
        this.nodeId = nodeId == null || nodeId.isBlank() ? "unknown" : nodeId;
        this.requestAdmission = requestAdmission;
    }

    public boolean handleIfHealthProbe(ChannelHandlerContext ctx, FullHttpRequest req) {
        String requestOrigin = req.headers().get(HttpHeaderNames.ORIGIN);
        String path = normalizePath(req.uri());
        if (HealthEndpoints.LIVE.equals(path)) {
            JsonResponse.status(ctx, HttpResponseStatus.OK, HealthSnapshot.live(nodeId), null, requestOrigin);
            return true;
        }
        if (HealthEndpoints.READY.equals(path)) {
            HealthSnapshot snapshot = HealthSnapshot.ready(nodeId, requestAdmission == null || requestAdmission.isOpen());
            HttpResponseStatus status = snapshot.status() == HealthStatus.UP
                    ? HttpResponseStatus.OK
                    : HttpResponseStatus.SERVICE_UNAVAILABLE;
            JsonResponse.status(ctx, status, snapshot, null, requestOrigin);
            return true;
        }
        return false;
    }

    private static String normalizePath(String uri) {
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        if (path.endsWith("/") && path.length() > 1) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}

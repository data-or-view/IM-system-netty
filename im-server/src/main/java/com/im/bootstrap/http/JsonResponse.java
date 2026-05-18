package com.im.bootstrap.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;

public class JsonResponse {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    public static void ok(ChannelHandlerContext ctx, Object data) {
        write(ctx, HttpResponseStatus.OK, data);
    }

    public static void created(ChannelHandlerContext ctx, Object data) {
        write(ctx, HttpResponseStatus.CREATED, data);
    }

    public static void error(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        try {
            String json = MAPPER.writeValueAsString(new ErrorBody(status.code(), message));
            writeRaw(ctx, status, json);
        } catch (Exception e) {
            writeRaw(ctx, status, "{\"code\":" + status.code() + ",\"message\":\"" + message + "\"}");
        }
    }

    public static void badRequest(ChannelHandlerContext ctx, String message) {
        error(ctx, HttpResponseStatus.BAD_REQUEST, message);
    }

    public static void notFound(ChannelHandlerContext ctx, String message) {
        error(ctx, HttpResponseStatus.NOT_FOUND, message);
    }

    public static void serverError(ChannelHandlerContext ctx, String message) {
        error(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, message);
    }

    private static void write(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
        try {
            String json = MAPPER.writeValueAsString(data);
            writeRaw(ctx, status, json);
        } catch (Exception e) {
            writeRaw(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "{\"code\":500,\"message\":\"serialization error\"}");
        }
    }

    private static void writeRaw(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        ByteBuf content = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, APPLICATION_JSON);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private record ErrorBody(int code, String message) {}
}

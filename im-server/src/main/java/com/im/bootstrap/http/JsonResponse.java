package com.im.bootstrap.http;

import com.im.common.enums.ImErrorCode;
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
            String json = MAPPER.writeValueAsString(new ErrorBody(status.code(), status.code(), message));
            writeRaw(ctx, status, json);
        } catch (Exception e) {
            writeRaw(ctx, status, "{\"code\":" + status.code() + ",\"imCode\":" + status.code() + ",\"message\":\"" + message + "\"}");
        }
    }

    /**
     * 写 IM 错误响应：将 IM 错误码映射为标准 HTTP 状态码，
     * 并在 JSON body 中携带原始 IM 错误码。
     */
    public static void imError(ChannelHandlerContext ctx, ImErrorCode imCode, String detail) {
        HttpResponseStatus httpStatus = toHttpStatus(imCode);
        String msg = detail != null ? detail : imCode.getMessage();
        try {
            String json = MAPPER.writeValueAsString(new ErrorBody(httpStatus.code(), imCode.getCode(), msg));
            writeRaw(ctx, httpStatus, json);
        } catch (Exception e) {
            writeRaw(ctx, httpStatus, "{\"code\":" + httpStatus.code() + ",\"imCode\":" + imCode.getCode() + ",\"message\":\"" + msg + "\"}");
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

    /**
     * 将 ImErrorCode 映射为标准 HTTP 状态码。
     * IM 专用码（440/480/481/482/483）在 HTTP 传输时映射为合适的标准码。
     */
    static HttpResponseStatus toHttpStatus(ImErrorCode code) {
        return switch (code) {
            case INVALID_MESSAGE -> HttpResponseStatus.BAD_REQUEST;
            case USER_OFFLINE -> HttpResponseStatus.BAD_REQUEST;
            case DELIVERY_FAILED -> HttpResponseStatus.INTERNAL_SERVER_ERROR;
            case MESSAGE_TOO_LARGE -> HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
            case DUPLICATE_MESSAGE -> HttpResponseStatus.CONFLICT;
            case MQ_UNAVAILABLE -> HttpResponseStatus.SERVICE_UNAVAILABLE;
            // 标准 HTTP 码直接映射
            default -> HttpResponseStatus.valueOf(code.getCode());
        };
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

    private record ErrorBody(int code, int imCode, String message) {}
}

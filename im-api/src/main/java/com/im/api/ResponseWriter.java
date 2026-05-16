package com.im.api;

import com.im.common.enums.ImErrorCode;

/**
 * 协议特定的响应写回策略。
 *
 * <p>业务 handler 不直接操作 Netty 的 {@code ChannelHandlerContext}，
 * 而是通过 {@code ResponseWriter} 写回结果，由 Adapter 层负责转换
 * 为协议特定的格式。</p>
 *
 * <p>每个协议提供一个实现：</p>
 * <ul>
 *   <li>{@code WsResponseWriter} — 包装为 {@code {"op":"xxx_ack","seq":N,"code":0,"data":...}}</li>
 *   <li>{@code HttpResponseWriter} — 写 HTTP Response + JSON body</li>
 * </ul>
 */
public interface ResponseWriter {

    /**
     * 写回正常结果。
     *
     * @param result handler 返回的业务结果对象，由实现序列化为协议格式
     */
    void write(Object result);

    /**
     * 写回错误结果。
     *
     * @param code   错误码
     * @param detail 错误详情
     */
    void writeError(ImErrorCode code, String detail);
}

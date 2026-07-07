package com.im.core.dispatcher;

import com.im.api.ApiRequest;

/**
 * 自定义异常处理器，用于为特定异常类型定制错误响应。
 */
@FunctionalInterface
public interface ApiExceptionHandler {
    void handle(Exception e, ApiRequest request);
}

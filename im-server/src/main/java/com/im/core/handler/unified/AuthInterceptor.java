package com.im.core.handler.unified;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.api.IAuthenticator;
import com.im.api.ImHeaders;
import com.im.api.Operation;
import com.im.common.exception.ImException;
import com.im.common.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 认证拦截器（统一 WS+HTTP）。
 *
 * <p>替换旧的 {@code AuthenticationInterceptor} + {@code AuthorizationInterceptor}，
 * 且不再维护白名单——由 {@link Operation#requireAuth()} 元数据驱动。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li>从 {@code ApiRequest.op()} 获取 Operation 元数据</li>
 *   <li>{@code requireAuth == false} 的操作直接放行</li>
 *   <li>其他操作验证 Authorization header token</li>
 *   <li>验证通过后将 userId 写入 request attributes 的 {@code _uid} 键</li>
 * </ul>
 */
public class AuthInterceptor implements ApiInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    public static final int ORDER = Integer.MIN_VALUE + 100;

    private static final String TOKEN_HEADER = ImHeaders.AUTHORIZATION;

    private final IAuthenticator authenticator;

    public AuthInterceptor(IAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public String name() {
        return "auth";
    }

    /** 认证必须最先执行 */
    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean preHandle(ApiRequest request) {
        // 从 Operation 元数据判断是否需要认证（替代旧 WHITE_LIST）
        Operation op = request.op();
        if (op != null && !op.requireAuth()) {
            log.debug("NO_AUTH: op={}", op.opName());
            return true;
        }

        // 取 token
        String token = request.header(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            log.warn("Request without token: op={}", request.operation());
            throw new UnauthorizedException("missing token");
        }
        if (token.startsWith(ImHeaders.BEARER_PREFIX)) {
            token = token.substring(ImHeaders.BEARER_PREFIX.length()).trim();
        }

        // 验证
        try {
            String userId = authenticator.authenticateAccessToken(token);
            request.setAttribute(ApiRequest.ATTR_USER_ID, userId);
            MDC.put("app.user.id", userId);
            log.debug("AUTH OK: userId={}, op={}", userId, request.operation());
            return true;
        } catch (ImException e) {
            // ImException 抛给调度器，带上具体错误码和详情
            throw e;
        } catch (Exception e) {
            // 非 ImException 包装为 UNAUTHORIZED，附带错误原因
            throw new UnauthorizedException(e.getMessage(), e);
        }
    }

    @Override
    public void afterCompletion(ApiRequest request, Object result, Exception error) {
        // 无清理逻辑
    }
}

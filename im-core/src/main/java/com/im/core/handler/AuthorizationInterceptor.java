package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IAuthenticator;
import com.im.api.IMCommand;
import com.im.api.IMInterceptor;
import com.im.api.Role;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 权限拦截器。
 *
 * <p>执行位置：在 AuthenticationInterceptor 之后。</p>
 *
 * <p>职责：检查当前命令所需的权限级别，用户角色不够则阻断。
 * 用户角色来自 JWT 中的 {@code lvl} 字段（appManagerLevel）。</p>
 *
 * <h3>权限映射表</h3>
 * <p>每个 CommandType 映射到一个最小 {@link Role}。默认所有命令需要 {@link Role#USER}，
 * 白名单在 {@link #initDefaultMapping()} 中配置。</p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>无状态</b>：只查 JWT payload，不查 DB、不查 Redis</li>
 *   <li><b>角色分层</b>：ADMIN 自动拥有 USER 权限，SUPER_ADMIN 自动拥有 ADMIN</li>
 *   <li><b>资源级权限不放拦截器</b>：群管理员/好友关系等业务权限由 Handler 自行检查</li>
 * </ul>
 */
public class AuthorizationInterceptor implements IMInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationInterceptor.class);

    /** 命令→所需最低角色（显式配置，覆盖默认 USER） */
    private final Map<CommandType, Role> permissionMapping = new HashMap<>();

    private final IAuthenticator authenticator;

    public AuthorizationInterceptor(IAuthenticator authenticator) {
        this.authenticator = authenticator;
        initDefaultMapping();
    }

    /**
     * 设置自定义权限映射（覆盖默认）。
     */
    public AuthorizationInterceptor withPermission(CommandType type, Role required) {
        permissionMapping.put(type, required);
        return this;
    }

    @Override
    public boolean preHandle(ChannelHandlerContext ctx, IMCommand msg) {
        Role required = permissionMapping.getOrDefault(msg.getType(), Role.USER);

        // PUBLIC 级别——不需要 token，直接放行
        if (required == Role.PUBLIC) {
            return true;
        }

        // 取用户角色（从 Authorization header 中的 token 解析）
        String authHeader = msg.getHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            log.warn("Authorization required for command={} (role={})", msg.getType(), required);
            return false;
        }
        String token = authHeader.startsWith("Bearer ")
                ? authHeader.substring(7).trim()
                : authHeader.trim();

        int appManagerLevel = authenticator.getAppManagerLevel(token);
        Role userRole = Role.fromAppManagerLevel(appManagerLevel);

        if (!userRole.canAccess(required)) {
            log.warn("Permission denied: userId from token, required={}, actual={}, cmd={}",
                    required, userRole, msg.getType());
            return false;
        }

        return true;
    }

    @Override
    public void afterComplete(ChannelHandlerContext ctx, IMCommand msg, Exception ex) {
        // 无清理逻辑
    }

    @Override
    public String name() {
        return "authorization";
    }

    /** 在 AuthenticationInterceptor (MIN) 之后执行 */
    @Override
    public int order() {
        return Integer.MIN_VALUE + 100;
    }

    // ========== 默认权限映射 ==========

    private void initDefaultMapping() {
        // PUBLIC：无需登录
        for (CommandType t : Set.of(
                CommandType.LOGIN,
                CommandType.REGISTER,
                CommandType.HEARTBEAT,
                CommandType.HEARTBEAT_ACK
        )) {
            permissionMapping.put(t, Role.PUBLIC);
        }

        // 其余命令默认 USER，但可以显式设置
        // 后续管理类 API 可以 .withPermission(ADMIN_API, Role.ADMIN)
    }
}

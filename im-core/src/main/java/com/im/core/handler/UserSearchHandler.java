package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.IUserManager;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.api.UserInformation;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 用户搜索处理器。
 *
 * <p>处理 USER_SEARCH 命令，按昵称或 user_id 搜索用户。</p>
 *
 * <h3>请求头</h3>
 * <ul>
 *   <li>{@code keyword} — 搜索关键词（必填）</li>
 *   <li>{@code limit} — 最大返回条数（可选，默认 20）</li>
 * </ul>
 *
 * <h3>响应头</h3>
 * <ul>
 *   <li>{@code users} — JSON 数组 {@link UserInformation} 列表</li>
 *   <li>{@code count} — 结果数量</li>
 * </ul>
 */
public class UserSearchHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(UserSearchHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IUserManager userManager;

    public UserSearchHandler(IUserManager userManager) {
        this.userManager = userManager;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.USER_SEARCH);
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String keyword = msg.getHeader("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing keyword");
        }

        String limitStr = msg.getHeader("limit");
        int limit = 20;
        if (limitStr != null && !limitStr.isEmpty()) {
            try {
                limit = Integer.parseInt(limitStr);
            } catch (NumberFormatException ignored) {}
        }

        List<UserInformation> users = userManager.searchUsers(keyword.trim(), limit);

        IMCommand ack = msg.createAcknowledgement(CommandType.USER_SEARCH_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("count", String.valueOf(users.size()));
        try {
            String json = MAPPER.writeValueAsString(users);
            ack.putHeader("users", json);
        } catch (Exception e) {
            throw new ImException(ImErrorCode.INTERNAL_ERROR, "serialize user list failed");
        }
        ctx.writeAndFlush(ack);
        log.debug("USER_SEARCH: keyword={} count={}", keyword, users.size());
    }
}

package com.im.core.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.GroupInformation;
import com.im.api.IGroupManager;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImErrorCode;
import com.im.api.ImException;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 群组搜索处理器。
 *
 * <p>处理 GROUP_SEARCH 命令，按群名关键词搜索公开群组。</p>
 *
 * <h3>请求头</h3>
 * <ul>
 *   <li>{@code keyword} — 搜索关键词（必填）</li>
 *   <li>{@code limit} — 最大返回条数（可选，默认 20）</li>
 * </ul>
 *
 * <h3>响应头</h3>
 * <ul>
 *   <li>{@code groups} — JSON 数组 {@link GroupInformation} 列表</li>
 *   <li>{@code count} — 结果数量</li>
 * </ul>
 */
public class GroupSearchHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupSearchHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IGroupManager groupManager;

    public GroupSearchHandler(IGroupManager groupManager) {
        this.groupManager = groupManager;
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.GROUP_SEARCH);
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String keyword = msg.getHeader("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "missing keyword");
        }

        int limit = 20;
        String limitStr = msg.getHeader("limit");
        if (limitStr != null && !limitStr.isEmpty()) {
            try {
                limit = Integer.parseInt(limitStr);
            } catch (NumberFormatException ignored) {}
        }

        List<GroupInformation> groups = groupManager.searchGroups(keyword.trim(), limit);

        IMCommand ack = msg.createAcknowledgement(CommandType.GROUP_SEARCH_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("count", String.valueOf(groups.size()));
        try {
            String json = MAPPER.writeValueAsString(groups);
            ack.putHeader("groups", json);
        } catch (Exception e) {
            throw new ImException(ImErrorCode.INTERNAL_ERROR, "serialize group list failed");
        }
        ctx.writeAndFlush(ack);
        log.debug("GROUP_SEARCH: keyword={} count={}", keyword, groups.size());
    }
}

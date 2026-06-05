package com.im.core.usecase;

import com.im.api.IGroupManager;
import com.im.api.IMessageStore;
import com.im.common.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 消息撤回用例。
 *
 * <p>执行业务检查（角色判定），更新消息状态。
 * 撤回通知的推送由 {@code RevokeHandler} 层负责。</p>
 */
public class RevokeUseCase {

    private static final Logger log = LoggerFactory.getLogger(RevokeUseCase.class);

    private final IMessageStore messageStore;
    private final IGroupManager groupManager;

    public RevokeUseCase(IMessageStore messageStore, IGroupManager groupManager) {
        this.messageStore = messageStore;
        this.groupManager = groupManager;
    }

    /**
     * 执行撤回。
     *
     * @param userId         撤回人
     * @param conversationId 会话 ID
     * @param seq            消息 seq
     * @param groupId        群 ID（群聊时必填，单聊为空）
     * @return 撤回结果，包含通知所需的接收方 ID 列表
     */
    public RevokeResult execute(String userId, String conversationId, long seq, String groupId) {
        // ① 角色判定：群主(200)/管理员(100) 可撤回任意消息，普通用户(0) 遵守时间窗口
        int role = 0;
        if (groupId != null) {
            String roleStr = groupManager.getRole(groupId, userId);
            if ("owner".equals(roleStr)) {
                role = 200;
            } else if ("admin".equals(roleStr)) {
                role = 100;
            }
        }

        // ② 更新消息状态
        String nickname = userId;
        boolean updated = messageStore.revokeMessage(conversationId, seq, userId, role, nickname);
        if (!updated) {
            throw new NotFoundException("message not found or already revoked");
        }
        log.info("Message revoked: conv={}, seq={}, revoker={}", conversationId, seq, userId);

        // ③ 计算通知接收方
        Set<String> targetUserIds;
        if (groupId != null) {
            targetUserIds = new java.util.HashSet<>(groupManager.getMemberIds(groupId));
            targetUserIds.remove(userId);
        } else {
            targetUserIds = parseSingleTargets(conversationId, userId);
        }

        return new RevokeResult(conversationId, seq, userId, targetUserIds);
    }

    /**
     * 从单聊 conversationId "single_{userA}_{userB}" 中解析接收方。
     * 注意 user IDs 可能包含下划线，不能用简单的 split("_", 3)。
     * 策略：去掉 "single_" 前缀，然后根据 revokerId 在剩余字符串中查找匹配。
     */
    private Set<String> parseSingleTargets(String conversationId, String revokerId) {
        if (conversationId == null || !conversationId.startsWith("single_")) {
            return Set.of();
        }
        String ids = conversationId.substring("single_".length());
        // ids = userA + "_" + userB, 其中 userA < userB (字典序)
        // 反转查找：尝试从右端或左端匹配 revokerId
        if (ids.endsWith(revokerId) && ids.charAt(ids.length() - revokerId.length() - 1) == '_') {
            // revokerId 是 userB, target 是 userA（前缀）
            return Set.of(ids.substring(0, ids.length() - revokerId.length() - 1));
        } else if (ids.startsWith(revokerId + "_")) {
            // revokerId 是 userA, target 是 userB（后缀）
            return Set.of(ids.substring(revokerId.length() + 1));
        }
        return Set.of();
    }}

package com.im.bootstrap;

import com.im.api.*;
import com.im.core.sync.LocalIncrementalSync;
import com.im.core.friend.LocalFriendManager;
import com.im.core.group.LocalGroupManager;
import com.im.core.conversation.LocalConversationManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量同步功能测试（纯逻辑，不依赖服务启动）。
 *
 * <p>直接创建 Local 管理器实例，验证增量同步各场景：
 * <ul>
 *   <li>好友增删同步</li>
 *   <li>黑名单同步</li>
 *   <li>群组加入/退出同步</li>
 *   <li>群成员变更同步</li>
 *   <li>会话变更同步</li>
 * </ul>
 */
class IncrementalSyncTest {

    // ============================
    //  好友增量同步
    // ============================

    @Test
    void testFriendIncrementalSync() {
        LocalIncrementalSync sync = new LocalIncrementalSync();
        LocalFriendManager friendManager = new LocalFriendManager(null, sync);

        // 添加好友 (模拟 respondFriendApply agreed=true)
        friendManager.applyAddFriend("user1", "user2", "hello");
        friendManager.respondFriendApply("user2", "user1", "ok", true);

        // 验证：初始版本为 0，同步后应看到新增的好友
        IncrementalSyncResult<FriendInformation> result1 = friendManager.getIncrementalFriends("user1", 0);
        assertEquals(1, result1.getEntities().size(), "user1 should have 1 friend added");
        assertEquals("user2", result1.getEntities().get(0).getFriendUserId());
        assertFalse(result1.getEntities().get(0).isDeleted());
        long version1 = result1.getLatestVersion();
        assertTrue(version1 > 0, "version should have incremented");

        // 再次同步相同版本应无变化
        IncrementalSyncResult<FriendInformation> result1b = friendManager.getIncrementalFriends("user1", version1);
        assertTrue(result1b.getEntities().isEmpty(), "no changes since last sync");

        // 删除好友
        friendManager.deleteFriend("user1", "user2");
        IncrementalSyncResult<FriendInformation> result2 = friendManager.getIncrementalFriends("user1", version1);
        assertEquals(1, result2.getEntities().size(), "should see 1 deletion");
        assertTrue(result2.getEntities().get(0).isDeleted(), "friend should be marked deleted");

        // 另一侧也应看到
        IncrementalSyncResult<FriendInformation> result3 = friendManager.getIncrementalFriends("user2", 0);
        assertEquals(1, result3.getEntities().size(), "user2 should see friend change");
    }

    // ============================
    //  黑名单增量同步
    // ============================

    @Test
    void testBlacklistIncrementalSync() {
        LocalIncrementalSync sync = new LocalIncrementalSync();
        LocalFriendManager friendManager = new LocalFriendManager(null, sync);

        // 加黑
        friendManager.addBlack("user1", "user2");
        IncrementalSyncResult<String> result1 = friendManager.getIncrementalBlacks("user1", 0);
        assertEquals(List.of("user2"), result1.getEntities(), "should see blocked user");

        // 同步后再次同步无变化
        long v1 = result1.getLatestVersion();
        IncrementalSyncResult<String> result1b = friendManager.getIncrementalBlacks("user1", v1);
        assertTrue(result1b.getEntities().isEmpty());

        // 移除黑名单
        friendManager.removeBlack("user1", "user2");
        IncrementalSyncResult<String> result2 = friendManager.getIncrementalBlacks("user1", v1);
        assertEquals(1, result2.getEntities().size(), "should see removal");
    }

    // ============================
    //  群组增量同步
    // ============================

    @Test
    void testGroupIncrementalSync() {
        LocalIncrementalSync sync = new LocalIncrementalSync();
        LocalGroupManager groupManager = new LocalGroupManager(null, null, sync);

        // 创建群
        groupManager.createGroup("g001", "owner1", "TestGroup", null,
                List.of("user1", "user2"), 1, 0);

        // owner 应看到群加入
        IncrementalSyncResult<String> ownerResult = groupManager.getIncrementalGroups("owner1", 0);
        assertEquals(1, ownerResult.getEntities().size(), "owner should see group join");
        assertEquals("g001", ownerResult.getEntities().get(0));

        // member 应看到群加入
        IncrementalSyncResult<String> user2Result = groupManager.getIncrementalGroups("user2", 0);
        assertEquals(1, user2Result.getEntities().size(), "user2 should see group join");

        // 退群
        groupManager.quitGroup("g001", "user2");
        long v1 = user2Result.getLatestVersion();
        IncrementalSyncResult<String> afterQuit = groupManager.getIncrementalGroups("user2", v1);
        assertFalse(afterQuit.getEntities().isEmpty(), "should see group quit or be empty");
    }

    // ============================
    //  群成员增量同步
    // ============================

    @Test
    void testGroupMemberIncrementalSync() {
        LocalIncrementalSync sync = new LocalIncrementalSync();
        LocalGroupManager groupManager = new LocalGroupManager(null, null, sync);

        groupManager.createGroup("g001", "owner1", "TestGroup",
                null, List.of("user1"), 1, 0);

        // 同步成员（从版本 0 开始）
        IncrementalSyncResult<GroupMemberInformation> members = groupManager.getIncrementalMembers("g001", 0);
        assertEquals(2, members.getEntities().size(), "should have owner + user1");

        // 添加新成员
        groupManager.addMember("g001", "user2");
        long v1 = members.getLatestVersion();
        IncrementalSyncResult<GroupMemberInformation> afterAdd = groupManager.getIncrementalMembers("g001", v1);
        assertEquals(1, afterAdd.getEntities().size(), "should see new member");
        assertEquals("user2", afterAdd.getEntities().get(0).getUserId());

        // 踢出成员
        groupManager.kickMember("g001", "owner1", "user2");
        long v2 = afterAdd.getLatestVersion();
        IncrementalSyncResult<GroupMemberInformation> afterKick = groupManager.getIncrementalMembers("g001", v2);
        assertEquals(1, afterKick.getEntities().size(), "should see member removal");
        assertEquals("user2", afterKick.getEntities().get(0).getUserId());
    }

    // ============================
    //  会话增量同步
    // ============================

    @Test
    void testConversationIncrementalSync() {
        LocalIncrementalSync sync = new LocalIncrementalSync();
        LocalConversationManager convManager = new LocalConversationManager(null, sync);

        // 创建会话
        convManager.createSingleConversation("user1", "user2", "single_user1_user2");

        IncrementalSyncResult<Conversation> result1 = convManager.getIncrementalConversations("user1", 0);
        assertEquals(1, result1.getEntities().size(), "should see new conversation");
        assertEquals("single_user1_user2", result1.getEntities().get(0).getConversationId());

        // 置顶
        long v1 = result1.getLatestVersion();
        convManager.setPinned("user1", "single_user1_user2", true);
        IncrementalSyncResult<Conversation> result2 = convManager.getIncrementalConversations("user1", v1);
        assertEquals(1, result2.getEntities().size(), "should see pinned change");
        assertTrue(result2.getEntities().get(0).isPinned());
    }
}

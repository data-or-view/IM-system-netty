package com.im.bootstrap.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP REST handler 集成测试。
 */
class HttpRestHandlerTest {

    private EmbeddedChannel channel;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IUserManager userManager = new LocalUserManager();
    private final IFriendManager friendManager = new LocalFriendManager();
    private final IGroupManager groupManager = new LocalGroupManager();
    private final IConversationManager conversationManager = new LocalConversationManager();
    private final IMessageStore messageStore = new LocalMessageStore();
    private final ISequenceManager sequenceManager = new LocalSequenceManager();
    private final IFileStorageService fileStorage = new LocalFileStorage();

    @BeforeEach
    void setUp() {
        channel = newChannel();
    }

    private EmbeddedChannel newChannel() {
        HttpRestHandler handler = new HttpRestHandler();
        new UserRestHandler(userManager).register(handler);
        new FriendRestHandler(friendManager).register(handler);
        new GroupRestHandler(groupManager).register(handler);
        new ConversationRestHandler(conversationManager).register(handler);
        new MessageRestHandler(messageStore, sequenceManager).register(handler);
        new FileRestHandler(fileStorage).register(handler);
        return new EmbeddedChannel(handler);
    }

    @Test
    void testHealthCheck() throws Exception {
        String response = sendGet("/api/health");
        assertTrue(response.contains("ok"), "Health check should return ok");
    }

    @Test
    void testUserRegisterAndQuery() throws Exception {
        String resp = sendPost("/api/user/register",
                "{\"userId\":\"testuser\",\"nickname\":\"Test\",\"faceUrl\":\"\"}");
        assertTrue(resp.contains("\"status\":\"OK\""));

        resp = sendGet("/api/user/info?userId=testuser");
        assertTrue(resp.contains("testuser"));
    }

    @Test
    void testUserSearch() throws Exception {
        sendPost("/api/user/register", "{\"userId\":\"alice\",\"nickname\":\"Alice\"}");
        sendPost("/api/user/register", "{\"userId\":\"bob\",\"nickname\":\"Bob\"}");

        String resp = sendGet("/api/user/search?keyword=Ali");
        assertTrue(resp.contains("alice"));
        assertTrue(resp.contains("\"count\":1"));
    }

    @Test
    void testFriendLifecycle() throws Exception {
        sendPost("/api/user/register", "{\"userId\":\"user1\",\"nickname\":\"User1\"}");
        sendPost("/api/user/register", "{\"userId\":\"user2\",\"nickname\":\"User2\"}");

        String applyResp = sendPost("/api/friend/apply",
                "{\"fromUserId\":\"user1\",\"toUserId\":\"user2\",\"reqMsg\":\"hello\"}");
        assertTrue(applyResp.contains("\"status\":\"OK\""));

        String approveResp = sendPost("/api/friend/approve",
                "{\"userId\":\"user2\",\"fromUserId\":\"user1\",\"agreed\":true}");
        assertTrue(approveResp.contains("\"status\":\"OK\""));

        String listResp = sendGet("/api/friend/list?userId=user1");
        assertTrue(listResp.contains("user2"));
        assertTrue(listResp.contains("\"count\":1"));
    }

    @Test
    void testGroupLifecycle() throws Exception {
        String createResp = sendPost("/api/group/create",
                "{\"groupId\":\"group1\",\"groupName\":\"TestGroup\",\"ownerId\":\"owner1\",\"members\":[\"member1\",\"member2\"]}");
        assertTrue(createResp.contains("\"status\":\"OK\""));

        String infoResp = sendGet("/api/group/info?groupId=group1");
        assertTrue(infoResp.contains("TestGroup"));

        String membersResp = sendGet("/api/group/members?groupId=group1");
        assertTrue(membersResp.contains("member1"));
    }

    @Test
    void testNotFound() {
        String resp = sendGet("/api/nonexistent");
        assertTrue(resp.contains("404") || resp.contains("not found"),
                "Should return 404 for unknown route");
    }

    @Test
    void testUserUpdate() throws Exception {
        sendPost("/api/user/register", "{\"userId\":\"updatable\",\"nickname\":\"Old\"}");
        String resp = sendPost("/api/user/update",
                "{\"userId\":\"updatable\",\"nickname\":\"NewName\",\"faceUrl\":\"http://avatar\"}");
        assertTrue(resp.contains("\"status\":\"OK\""));
    }

    // ══════════════════════════════════════════
    //  Path pattern matching tests
    // ══════════════════════════════════════════

    @Test
    void testPathVariableMatching() throws Exception {
        // Register a parameterized route inline
        HttpRestHandler handler = (HttpRestHandler) channel.pipeline().get(HttpRestHandler.class);
        handler.get("/api/test/user/{userId}", (req, ctx) -> Map.of("userId", req.uri(), "status", "OK"));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test/user/u123");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response, "Path variable route should match");
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("u123"), "Should match variable userId in path");
    }

    @Test
    void testPathVariableNotFound() {
        HttpRestHandler handler = (HttpRestHandler) channel.pipeline().get(HttpRestHandler.class);
        handler.get("/api/test/user/{userId}", (req, ctx) -> Map.of("status", "OK"));

        String resp = sendGet("/api/test/user");
        assertTrue(resp.contains("404") || resp.contains("not found"),
                "Non-matching dynamic path should return 404");
    }

    // ══════════════════════════════════════════
    //  HttpInterceptor tests
    // ══════════════════════════════════════════

    @Test
    void testInterceptorBlocksRequest() {
        HttpRestHandler handler = (HttpRestHandler) channel.pipeline().get(HttpRestHandler.class);
        handler.addInterceptor(new HttpInterceptor() {
            @Override public String name() { return "blockAll"; }
            @Override public boolean preHandle(FullHttpRequest req, ChannelHandlerContext ctx) {
                return false;
            }
            @Override public void afterComplete(FullHttpRequest req, ChannelHandlerContext ctx,
                                                 Object result, Exception ex) {}
        });

        // Even the health check route should be blocked
        String resp = sendGet("/api/health");
        assertFalse(resp.contains("\"status\":\"ok\""),
                "Interceptor should block all requests");
    }

    @Test
    void testInterceptorOrdering() {
        List<String> callLog = Collections.synchronizedList(new ArrayList<>());

        HttpRestHandler handler = (HttpRestHandler) channel.pipeline().get(HttpRestHandler.class);

        handler.addInterceptor(new HttpInterceptor() {
            @Override public String name() { return "B"; }
            @Override public int order() { return 20; }
            @Override public boolean preHandle(FullHttpRequest req, ChannelHandlerContext ctx) {
                callLog.add("pre-B");
                return false; // B blocks
            }
            @Override public void afterComplete(FullHttpRequest req, ChannelHandlerContext ctx,
                                                 Object result, Exception ex) {
                callLog.add("after-B"); // should NOT be called
            }
        });
        handler.addInterceptor(new HttpInterceptor() {
            @Override public String name() { return "A"; }
            @Override public int order() { return 10; }
            @Override public boolean preHandle(FullHttpRequest req, ChannelHandlerContext ctx) {
                callLog.add("pre-A");
                return true;
            }
            @Override public void afterComplete(FullHttpRequest req, ChannelHandlerContext ctx,
                                                 Object result, Exception ex) {
                callLog.add("after-A"); // should be called when B blocks
            }
        });

        sendGet("/api/health");
        assertEquals(List.of("pre-A", "pre-B", "after-A"), callLog,
                "Order: A(lower) first, B blocks, B's afterComplete skipped, A's afterComplete runs");
    }

    @Test
    void testInterceptorPassThrough() throws Exception {
        HttpRestHandler handler = (HttpRestHandler) channel.pipeline().get(HttpRestHandler.class);
        handler.addInterceptor(new HttpInterceptor() {
            @Override public String name() { return "pass"; }
            @Override public boolean preHandle(FullHttpRequest req, ChannelHandlerContext ctx) {
                return true;
            }
            @Override public void afterComplete(FullHttpRequest req, ChannelHandlerContext ctx,
                                                 Object result, Exception ex) {}
        });

        // Health check should work normally with a pass-through interceptor
        String resp = sendGet("/api/health");
        assertTrue(resp.contains("ok"), "Pass-through interceptor should not block");
    }

    // ══════════════════════════════════════════
    //  HTTP test helpers
    // ══════════════════════════════════════════

    private String sendGet(String uri) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        return sendRequest(request);
    }

    private String sendPost(String uri, String jsonBody) {
        ByteBuf content = Unpooled.copiedBuffer(jsonBody, StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri, content);
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        return sendRequest(request);
    }

    private String sendRequest(FullHttpRequest request) {
        if (!channel.isOpen()) {
            channel = newChannel();
        }
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        if (response == null) {
            return "";
        }
        ByteBuf buf = response.content();
        if (buf != null && buf.readableBytes() > 0) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return "";
    }

    // ══════════════════════════════════════════
    //  Local implementations for testing
    // ══════════════════════════════════════════

    private static class LocalUserManager implements IUserManager {
        final Map<String, UserInformation> users = new HashMap<>();

        @Override
        public void register(String userId, String nickname, String faceUrl, String ex) {
            UserInformation info = new UserInformation();
            info.setUserId(userId);
            info.setNickname(nickname);
            info.setFaceUrl(faceUrl);
            users.put(userId, info);
        }

        @Override
        public UserInformation getUserInformation(String userId) {
            return users.get(userId);
        }

        @Override
        public List<UserInformation> getUsersInfo(List<String> userIds) {
            return userIds.stream().map(users::get).filter(Objects::nonNull).collect(Collectors.toList());
        }

        @Override
        public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) {
            return Map.of();
        }

        @Override
        public void updateUserInformation(String userId, String nickname, String faceUrl, String ex, int globalRecvMsgOpt) {
            UserInformation info = users.get(userId);
            if (info != null) {
                if (nickname != null) info.setNickname(nickname);
                if (faceUrl != null) info.setFaceUrl(faceUrl);
            }
        }

        @Override
        public List<UserInformation> searchUsers(String keyword, int limit) {
            return users.values().stream()
                    .filter(u -> u.getUserId().contains(keyword) || u.getNickname().contains(keyword))
                    .limit(limit)
                    .collect(Collectors.toList());
        }
    }

    private static class LocalFriendManager implements IFriendManager {
        final Map<String, List<String>> friends = new HashMap<>();

        @Override
        public void applyAddFriend(String fromUserId, String toUserId, String reqMsg) {}

        @Override
        public void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed) {
            if (agreed) {
                friends.computeIfAbsent(userId, k -> new ArrayList<>()).add(fromUserId);
                friends.computeIfAbsent(fromUserId, k -> new ArrayList<>()).add(userId);
            }
        }

        @Override
        public List<FriendApply> getFriendApplyList(String userId, boolean onlyPending) {
            return List.of();
        }

        @Override
        public void deleteFriend(String ownerUserId, String friendUserId) {
            friends.getOrDefault(ownerUserId, new ArrayList<>()).remove(friendUserId);
            friends.getOrDefault(friendUserId, new ArrayList<>()).remove(ownerUserId);
        }

        @Override
        public List<FriendInformation> getFriendList(String userId) {
            return friends.getOrDefault(userId, List.of()).stream()
                    .map(fid -> {
                        FriendInformation fi = new FriendInformation();
                        fi.setOwnerUserId(userId);
                        fi.setFriendUserId(fid);
                        return fi;
                    }).collect(Collectors.toList());
        }

        @Override public boolean isFriend(String userIdA, String userIdB) {
            return friends.getOrDefault(userIdA, List.of()).contains(userIdB);
        }

        @Override public void setFriendRemark(String ownerUserId, String friendUserId, String remark) {}
        @Override public void setFriendPinned(String ownerUserId, String friendUserId, boolean pinned) {}
        @Override public void addBlack(String ownerUserId, String blockedUserId) {}
        @Override public void removeBlack(String ownerUserId, String blockedUserId) {}

        @Override
        public List<String> getBlackList(String userId) {
            return List.of();
        }

        @Override public boolean isBlocked(String fromUserId, String toUserId) { return false; }
    }

    private static class LocalGroupManager implements IGroupManager {
        final Map<String, GroupInformation> groups = new HashMap<>();
        final Map<String, List<String>> members = new HashMap<>();

        @Override
        public void createGroup(String groupId, String ownerId, String groupName, String faceUrl,
                                List<String> memberIds, int groupType, int needVerification) {
            GroupInformation info = new GroupInformation();
            info.setGroupId(groupId);
            info.setGroupName(groupName);
            info.setOwnerUserId(ownerId);
            groups.put(groupId, info);
            List<String> allMembers = new ArrayList<>(memberIds);
            if (!allMembers.contains(ownerId)) allMembers.add(0, ownerId);
            members.put(groupId, allMembers);
        }

        @Override
        public GroupInformation getGroupInformation(String groupId) {
            return groups.get(groupId);
        }

        @Override
        public List<GroupMemberInformation> getMemberList(String groupId) {
            return members.getOrDefault(groupId, List.of()).stream().map(uid -> {
                var m = new GroupMemberInformation();
                m.setUserId(uid);
                m.setGroupId(groupId);
                return m;
            }).collect(Collectors.toList());
        }

        @Override
        public Set<String> getMemberIds(String groupId) {
            return new HashSet<>(members.getOrDefault(groupId, List.of()));
        }

        @Override public void disbandGroup(String groupId, String operatorId) { groups.remove(groupId); }

        @Override
        public void setGroupInformation(String groupId, String groupName, String notification,
                String introduction, String faceUrl, int needVerification,
                int lookMemberInfo, int applyMemberFriend, String notificationUserId) {
            var info = groups.get(groupId);
            if (info != null && groupName != null) info.setGroupName(groupName);
        }

        @Override public void addMember(String groupId, String userId) {
            members.computeIfAbsent(groupId, k -> new ArrayList<>()).add(userId);
        }
        @Override public void addMembers(String groupId, List<String> userIds) {
            members.computeIfAbsent(groupId, k -> new ArrayList<>()).addAll(userIds);
        }
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) {
            members.getOrDefault(groupId, new ArrayList<>()).remove(targetUserId);
        }
        @Override public void quitGroup(String groupId, String userId) { kickMember(groupId, userId, userId); }
        @Override public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {}
        @Override public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {}
        @Override public void muteMember(String groupId, String targetUserId, long muteEndTime) {}
        @Override public void joinGroup(String groupId, String userId, String reqMsg) {}
        @Override public void respondJoinRequest(String groupId, String userId, String operatorId, String handleMsg, boolean agreed) {}
        @Override public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) { return List.of(); }
        @Override public boolean isMember(String groupId, String userId) { return members.getOrDefault(groupId, List.of()).contains(userId); }
        @Override public String getRole(String groupId, String userId) { return "member"; }
        @Override public Set<String> getJoinedGroups(String userId) { return Set.of(); }

        @Override
        public List<GroupInformation> searchGroups(String keyword, int limit) {
            return groups.values().stream()
                    .filter(g -> g.getGroupName().contains(keyword))
                    .limit(limit).collect(Collectors.toList());
        }
    }

    private static class LocalConversationManager implements IConversationManager {
        @Override
        public List<Conversation> getConversations(String ownerUserId) { return List.of(); }
        @Override public Conversation getConversation(String ownerUserId, String conversationId) { return null; }
        @Override public void updateOnMessage(String ownerUserId, String conversationId, IMCommand msg, boolean isSelf) {}
        @Override public void markRead(String ownerUserId, String conversationId, long readSeq) {}
        @Override public void setPinned(String ownerUserId, String conversationId, boolean pinned) {}
        @Override public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {}
        @Override public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {}
    }

    private static class LocalMessageStore implements IMessageStore {
        @Override public void save(IMCommand msg) {}
        @Override public List<IMCommand> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) { return List.of(); }
        @Override public List<IMCommand> pullOffline(String userId, int limit) { return List.of(); }
        @Override public void markDelivered(String userId, List<String> msgIds) {}
    }

    private static class LocalSequenceManager implements ISequenceManager {
        @Override public long nextSequence(String conversationId) { return 1; }
        @Override public long getMaximumSequence(String conversationId) { return 0; }
    }

    private static class LocalFileStorage implements IFileStorageService {
        @Override
        public String upload(String bucket, String objectId, byte[] data, String mimeType) {
            return "http://localhost:9000/" + bucket + "/" + objectId;
        }
        @Override public byte[] download(String bucket, String objectId) { return new byte[0]; }
        @Override public void delete(String bucket, String objectId) {}
        @Override public String getUrl(String bucket, String objectId) { return "http://localhost:9000/" + bucket + "/" + objectId; }
        @Override public boolean exists(String bucket, String objectId) { return false; }
    }
}

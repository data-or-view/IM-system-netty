package com.im.bootstrap.http;

import com.im.api.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.im.bootstrap.http.HttpParamUtils.*;

/**
 * 群组域 REST 控制器。
 *
 * <p>处理 /api/group/* 路由：创建、加入、退出、踢人、解散、修改信息、查询、搜索成员。</p>
 */
public class GroupRestHandler implements RestController {

    private static final Logger log = LoggerFactory.getLogger(GroupRestHandler.class);

    private final IGroupManager groupManager;

    public GroupRestHandler(IGroupManager groupManager) {
        this.groupManager = groupManager;
    }

    @Override
    public void register(HttpRestHandler router) {
        router.post("/api/group/create", this::handleCreate);
        router.post("/api/group/join", this::handleJoin);
        router.post("/api/group/quit", this::handleQuit);
        router.post("/api/group/kick", this::handleKick);
        router.post("/api/group/disband", this::handleDisband);
        router.post("/api/group/info/update", this::handleInfoUpdate);
        router.get("/api/group/info", this::handleInfo);
        router.get("/api/group/search", this::handleSearch);
        router.get("/api/group/members", this::handleMembers);
    }

    private Object handleCreate(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String groupId = str(body, "groupId");
        String groupName = str(body, "groupName");
        String ownerId = str(body, "ownerId");
        if (groupId == null || groupName == null || ownerId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId, groupName, and ownerId are required");
        }
        String faceUrl = str(body, "faceUrl", "");
        int groupType = intObj(body, "groupType", 0);
        int needVerification = intObj(body, "needVerification", 0);
        @SuppressWarnings("unchecked")
        List<String> members = body.containsKey("members")
                ? ((List<String>) body.get("members")) : List.of();
        groupManager.createGroup(groupId, ownerId, groupName, faceUrl, members, groupType, needVerification);
        log.debug("GROUP_CREATE: groupId={}, name={}, owner={}", groupId, groupName, ownerId);
        return Map.of("groupId", groupId, "status", "OK");
    }

    private Object handleJoin(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String groupId = str(body, "groupId");
        String userId = str(body, "userId");
        if (groupId == null || userId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId and userId are required");
        }
        String reqMsg = str(body, "reqMsg", "");
        groupManager.joinGroup(groupId, userId, reqMsg);
        return Map.of("status", "OK");
    }

    private Object handleQuit(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String groupId = str(body, "groupId");
        String userId = str(body, "userId");
        if (groupId == null || userId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId and userId are required");
        }
        groupManager.quitGroup(groupId, userId);
        return Map.of("status", "OK");
    }

    private Object handleKick(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String groupId = str(body, "groupId");
        String operatorId = str(body, "operatorId");
        String targetUserId = str(body, "targetUserId");
        if (groupId == null || operatorId == null || targetUserId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId, operatorId, targetUserId are required");
        }
        groupManager.kickMember(groupId, operatorId, targetUserId);
        return Map.of("status", "OK");
    }

    private Object handleDisband(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String groupId = str(body, "groupId");
        String operatorId = str(body, "operatorId");
        if (groupId == null || operatorId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "groupId and operatorId are required");
        }
        groupManager.disbandGroup(groupId, operatorId);
        return Map.of("status", "OK");
    }

    @SuppressWarnings("unchecked")
    private Object handleInfoUpdate(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, Object> body = parseJsonBody(req);
        String groupId = str(body, "groupId");
        if (groupId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "groupId is required");
        groupManager.setGroupInformation(
                groupId, str(body, "groupName"), str(body, "notification"),
                str(body, "introduction"), str(body, "faceUrl"),
                intObj(body, "needVerification", -1),
                intObj(body, "lookMemberInfo", -1),
                intObj(body, "applyMemberFriend", -1),
                str(body, "operatorId"));
        return Map.of("status", "OK");
    }

    private Object handleInfo(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String groupId = params.get("groupId");
        if (groupId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "groupId is required");
        GroupInformation info = groupManager.getGroupInformation(groupId);
        if (info == null) throw new ImException(ImErrorCode.NOT_FOUND, "group not found");
        return info;
    }

    private Object handleSearch(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String keyword = params.get("keyword");
        if (keyword == null || keyword.isBlank()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "keyword is required");
        }
        int limit = intParam(params, "limit", 20);
        List<GroupInformation> groups = groupManager.searchGroups(keyword.trim(), limit);
        return Map.of("groups", groups, "count", groups.size());
    }

    private Object handleMembers(FullHttpRequest req, ChannelHandlerContext ctx) {
        Map<String, String> params = parseQuery(req);
        String groupId = params.get("groupId");
        if (groupId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "groupId is required");
        List<GroupMemberInformation> members = groupManager.getMemberList(groupId);
        return Map.of("groupId", groupId, "members", members, "count", members.size());
    }
}

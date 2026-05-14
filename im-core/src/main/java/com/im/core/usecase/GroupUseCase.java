package com.im.core.usecase;

import com.im.api.GroupInformation;
import com.im.api.GroupMemberInformation;
import com.im.api.IGroupManager;

import java.util.List;
import java.util.Set;

public class GroupUseCase {

    private final IGroupManager groupManager;

    public GroupUseCase(IGroupManager groupManager) {
        this.groupManager = groupManager;
    }

    public void createGroup(String groupId, String userId, String groupName, String faceUrl,
                            List<String> members, int groupType, int needVerification) {
        groupManager.createGroup(groupId, userId, groupName, faceUrl, members, groupType, needVerification);
    }

    public void joinGroup(String groupId, String userId, String reqMsg) {
        groupManager.joinGroup(groupId, userId, reqMsg);
    }

    public void quitGroup(String groupId, String userId) {
        groupManager.quitGroup(groupId, userId);
    }

    public void kickMember(String groupId, String operatorId, String targetUserId) {
        groupManager.kickMember(groupId, operatorId, targetUserId);
    }

    public void updateGroupInfo(String groupId, String groupName, String notification, String introduction,
                                String faceUrl, int needVerification, int lookMemberInfo,
                                int applyMemberFriend, String userId) {
        groupManager.setGroupInformation(groupId, groupName, notification, introduction,
                faceUrl, needVerification, lookMemberInfo, applyMemberFriend, userId);
    }

    public List<GroupInformation> searchGroups(String keyword, int limit) {
        return groupManager.searchGroups(keyword, limit);
    }
}

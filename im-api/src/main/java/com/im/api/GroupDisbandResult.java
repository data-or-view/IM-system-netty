package com.im.api;

import java.util.List;

public class GroupDisbandResult {

    private final String groupId;
    private final String operatorId;
    private final String groupName;
    private final List<String> affectedMemberIds;

    public GroupDisbandResult(String groupId, String operatorId, String groupName, List<String> affectedMemberIds) {
        this.groupId = groupId;
        this.operatorId = operatorId;
        this.groupName = groupName;
        this.affectedMemberIds = List.copyOf(affectedMemberIds != null ? affectedMemberIds : List.of());
    }

    public String getGroupId() {
        return groupId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getGroupName() {
        return groupName;
    }

    public List<String> getAffectedMemberIds() {
        return affectedMemberIds;
    }
}

package com.im.core.group;

import com.im.api.GroupMemberInformation;

import java.util.List;

public class GroupMemberListSnapshot {

    private List<GroupMemberInformation> members;

    public GroupMemberListSnapshot() {
    }

    public GroupMemberListSnapshot(List<GroupMemberInformation> members) {
        this.members = List.copyOf(members);
    }

    public List<GroupMemberInformation> getMembers() {
        return members != null ? members : List.of();
    }

    public void setMembers(List<GroupMemberInformation> members) {
        this.members = members;
    }
}

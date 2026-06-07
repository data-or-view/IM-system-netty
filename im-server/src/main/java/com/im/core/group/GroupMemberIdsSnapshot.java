package com.im.core.group;

import java.util.LinkedHashSet;
import java.util.Set;

public class GroupMemberIdsSnapshot {

    private Set<String> memberIds;

    public GroupMemberIdsSnapshot() {
    }

    public GroupMemberIdsSnapshot(Set<String> memberIds) {
        this.memberIds = new LinkedHashSet<>(memberIds);
    }

    public Set<String> getMemberIds() {
        return memberIds != null ? memberIds : Set.of();
    }

    public void setMemberIds(Set<String> memberIds) {
        this.memberIds = memberIds;
    }
}

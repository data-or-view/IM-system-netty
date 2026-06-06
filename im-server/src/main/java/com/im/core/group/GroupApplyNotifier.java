package com.im.core.group;

import com.im.api.GroupApply;

import java.util.List;

public interface GroupApplyNotifier {

    void notifyApplyCreated(List<String> managerUserIds, GroupApply apply);

    void notifyApplyHandled(String applicantUserId, GroupApply apply);

    GroupApplyNotifier NOOP = new GroupApplyNotifier() {
        @Override public void notifyApplyCreated(List<String> managerUserIds, GroupApply apply) {}
        @Override public void notifyApplyHandled(String applicantUserId, GroupApply apply) {}
    };
}

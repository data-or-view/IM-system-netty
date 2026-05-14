package com.im.core.usecase;

import com.im.api.GroupInformation;
import com.im.api.IGroupManager;

import java.util.List;

public class GroupSearchUseCase {

    private final IGroupManager groupManager;

    public GroupSearchUseCase(IGroupManager groupManager) {
        this.groupManager = groupManager;
    }

    public List<GroupInformation> execute(String keyword, int limit) {
        return groupManager.searchGroups(keyword, limit);
    }
}

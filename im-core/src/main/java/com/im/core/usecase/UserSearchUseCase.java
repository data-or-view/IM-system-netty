package com.im.core.usecase;

import com.im.api.IUserManager;
import com.im.api.UserInformation;

import java.util.List;

public class UserSearchUseCase {

    private final IUserManager userManager;

    public UserSearchUseCase(IUserManager userManager) {
        this.userManager = userManager;
    }

    public List<UserInformation> execute(String keyword, int limit) {
        return userManager.searchUsers(keyword, limit);
    }
}

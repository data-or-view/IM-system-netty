package com.im.core.usecase;

import com.im.api.IUserManager;
import com.im.api.UserInformation;
import com.im.api.ImException;

public class RegisterUseCase {

    private final IUserManager userManager;

    public RegisterUseCase(IUserManager userManager) {
        this.userManager = userManager;
    }

    public record RegisterResult(String userId, String nickname, String faceUrl, boolean alreadyExists) {}

    public RegisterResult execute(String userId, String nickname, String faceUrl, String password) {
        if (userManager == null) {
            return new RegisterResult(userId, nickname != null ? nickname : userId, faceUrl != null ? faceUrl : "", false);
        }

        boolean exists = false;
        try {
            var existing = userManager.getUserInformation(userId);
            exists = existing != null;
        } catch (ImException e) {
            exists = false;
        }

        if (exists) {
            var existing = userManager.getUserInformation(userId);
            return new RegisterResult(userId,
                    existing != null && existing.getNickname() != null ? existing.getNickname() : nickname,
                    existing != null && existing.getFaceUrl() != null ? existing.getFaceUrl() : "",
                    true);
        }

        userManager.register(userId, nickname, faceUrl, null);

        if (password != null && !password.isBlank()) {
            try {
                var user = userManager.getUserInformation(userId);
                if (user != null && user.getPassword() == null) {
                    user.setPassword(password);
                }
            } catch (Exception ignored) {}
        }

        return new RegisterResult(userId, nickname != null ? nickname : userId, faceUrl != null ? faceUrl : "", false);
    }
}

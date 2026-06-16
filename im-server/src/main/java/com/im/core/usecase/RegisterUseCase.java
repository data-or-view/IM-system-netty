package com.im.core.usecase;

import com.im.api.IUserManager;
import com.im.common.exception.ImException;
import com.im.common.exception.ValidationException;
import com.im.common.id.IdGenerator;
import com.im.api.IPasswordHasher;
import com.im.api.IUserCredentialStore;

public class RegisterUseCase {

    private final IUserManager userManager;
    private final IUserCredentialStore credentialStore;
    private final IPasswordHasher passwordHasher;

    public RegisterUseCase(IUserManager userManager) {
        this(userManager, null, null);
    }

    public RegisterUseCase(IUserManager userManager, IUserCredentialStore credentialStore, IPasswordHasher passwordHasher) {
        this.userManager = userManager;
        this.credentialStore = credentialStore;
        this.passwordHasher = passwordHasher;
    }
    public RegisterResult execute(String userId, String nickname, String faceUrl, String password) {
        userId = IdGenerator.userId();
        nickname = nickname != null && !nickname.isBlank() ? nickname : userId;

        if (userManager == null) {
            return new RegisterResult(userId, nickname != null ? nickname : userId, faceUrl != null ? faceUrl : "", false);
        }
        if (credentialStore != null && passwordHasher != null && (password == null || password.isBlank())) {
            throw new ValidationException("password is required");
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
            if (credentialStore != null && passwordHasher != null) {
                String existingHash = credentialStore.getPasswordHash(userId);
                if (existingHash == null || existingHash.isBlank()) {
                    credentialStore.setPasswordHash(userId, passwordHasher.hash(password));
                }
            }
            return new RegisterResult(userId,
                    existing != null && existing.getNickname() != null ? existing.getNickname() : nickname,
                    existing != null && existing.getFaceUrl() != null ? existing.getFaceUrl() : "",
                    true);
        }

        userManager.register(userId, nickname, faceUrl, null);

        if (credentialStore != null && passwordHasher != null) {
            credentialStore.setPasswordHash(userId, passwordHasher.hash(password));
        }

        return new RegisterResult(userId, nickname != null ? nickname : userId, faceUrl != null ? faceUrl : "", false);
    }
}

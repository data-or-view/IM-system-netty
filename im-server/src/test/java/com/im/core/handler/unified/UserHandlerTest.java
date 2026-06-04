package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IUserManager;
import com.im.api.Operation;
import com.im.api.UserInformation;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserHandlerTest {

    @Test
    void registerOnlyTreatsNotFoundAsUserMissing() {
        RecordingUserManager userManager = new RecordingUserManager();
        userManager.lookupException = new ForbiddenException("lookup forbidden");
        UserHandler handler = new UserHandler(userManager);
        ApiRequest request = new ApiRequest(Operation.USER_REGISTER,
                Map.of("userId", "alice"), Map.of(), null, null);

        assertThrows(ForbiddenException.class, () -> handler.handle(request));
        assertEquals(0, userManager.registerCalls);
    }

    @Test
    void registerCreatesUserWhenLookupReturnsNotFound() {
        RecordingUserManager userManager = new RecordingUserManager();
        userManager.lookupException = new NotFoundException("user not found");
        UserHandler handler = new UserHandler(userManager);
        ApiRequest request = new ApiRequest(Operation.USER_REGISTER,
                Map.of("userId", "alice"), Map.of(), null, null);

        handler.handle(request);

        assertEquals(1, userManager.registerCalls);
    }

    private static final class RecordingUserManager implements IUserManager {
        private RuntimeException lookupException;
        private int registerCalls;

        @Override
        public void register(String userId, String nickname, String faceUrl, String ex) {
            registerCalls++;
        }

        @Override
        public UserInformation getUserInformation(String userId) {
            if (lookupException != null) {
                throw lookupException;
            }
            return new UserInformation(userId, userId);
        }

        @Override
        public List<UserInformation> getUsersInfo(List<String> userIds) {
            return List.of();
        }

        @Override
        public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) {
            return Map.of();
        }

        @Override
        public void updateUserInformation(String userId, String nickname, String faceUrl,
                                          String ex, int globalRecvMsgOpt) {
        }

        @Override
        public List<UserInformation> searchUsers(String keyword, int limit) {
            return List.of();
        }
    }
}

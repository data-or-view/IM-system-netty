package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IUserManager;
import com.im.api.Operation;
import com.im.api.UserInformation;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserHandlerTest {

    @Test
    void registerCreatesUserWithoutClientChosenUserId() {
        RecordingUserManager userManager = new RecordingUserManager();
        UserHandler handler = new UserHandler(userManager);
        ApiRequest request = new ApiRequest(Operation.USER_REGISTER,
                Map.of("nickname", "Alice"), Map.of(), null, null);

        handler.handle(request);

        assertEquals(1, userManager.registerCalls);
        assertTrue(userManager.registeredUserId.matches("usr_[0-9a-z]+_[0-9a-z]{8}"));
    }

    @Test
    void registerGeneratesUserIdOnServerAndIgnoresClientUserId() {
        RecordingUserManager userManager = new RecordingUserManager();
        userManager.lookupException = new NotFoundException("user not found");
        UserHandler handler = new UserHandler(userManager);
        ApiRequest request = new ApiRequest(Operation.USER_REGISTER,
                Map.of("userId", "client-picked", "nickname", "Alice"), Map.of(), null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals(1, userManager.registerCalls);
        assertTrue(userManager.registeredUserId.matches("usr_[0-9a-z]+_[0-9a-z]{8}"));
        assertEquals(userManager.registeredUserId, response.get("userId"));
    }

    @Test
    void meReturnsAuthenticatedUserInformation() {
        RecordingUserManager userManager = new RecordingUserManager();
        UserInformation alice = new UserInformation("u1", "Alice");
        userManager.lookupResult = alice;
        UserHandler handler = new UserHandler(userManager);
        ApiRequest request = new ApiRequest(Operation.USER_ME, Map.of(), Map.of(), null, null);
        request.setAttribute(ApiRequest.ATTR_USER_ID, "u1");

        Object response = handler.handle(request);

        assertEquals(alice, response);
        assertEquals("u1", userManager.lookupUserId);
    }

    @Test
    void meRequiresAuthentication() {
        UserHandler handler = new UserHandler(new RecordingUserManager());
        ApiRequest request = new ApiRequest(Operation.USER_ME, Map.of(), Map.of(), null, null);

        assertThrows(UnauthorizedException.class, () -> handler.handle(request));
    }

    private static final class RecordingUserManager implements IUserManager {
        private RuntimeException lookupException;
        private UserInformation lookupResult;
        private String lookupUserId;
        private int registerCalls;
        private String registeredUserId;

        @Override
        public void register(String userId, String nickname, String faceUrl, String ex) {
            registerCalls++;
            registeredUserId = userId;
        }

        @Override
        public UserInformation getUserInformation(String userId) {
            lookupUserId = userId;
            if (lookupException != null) {
                throw lookupException;
            }
            return lookupResult;
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

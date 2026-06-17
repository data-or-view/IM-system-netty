package com.im.core.call;

public interface SingleCallStateStore {

    SingleCallSession getByRoom(String roomId);

    SingleCallSession getActiveByUser(String userId);

    SingleCallSession createIfUsersIdle(SingleCallSession session);

    SingleCallSession accept(String roomId);

    SingleCallSession timeoutIfRinging(String roomId);

    SingleCallSession end(String roomId);
}

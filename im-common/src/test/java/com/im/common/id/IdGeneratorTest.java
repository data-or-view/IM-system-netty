package com.im.common.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdGeneratorTest {

    @Test
    void groupIdUsesCentralizedPrefix() {
        String id = IdGenerator.groupId();
        assertTrue(id.matches("grp_[0-9a-z]+_[0-9a-z]{8}"));
    }

    @Test
    void userFileMessageRoomAndSessionIdsUseCentralizedPrefixes() {
        assertTrue(IdGenerator.userId().matches("usr_[0-9a-z]+_[0-9a-z]{8}"));
        assertTrue(IdGenerator.fileId().matches("file_[0-9a-z]+_[0-9a-z]{8}"));
        assertTrue(IdGenerator.messageId().matches("msg_[0-9a-z]+_[0-9a-z]{8}"));
        assertTrue(IdGenerator.roomId().matches("room_[0-9a-z]+_[0-9a-z]{8}"));
        assertTrue(IdGenerator.sessionId().matches("sess_[0-9a-z]+_[0-9a-z]{8}"));
    }

    @Test
    void generatedIdsAreNotConstant() {
        assertNotEquals(IdGenerator.groupId(), IdGenerator.groupId());
    }

    @Test
    void blankPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> IdGenerator.next(" "));
    }
}

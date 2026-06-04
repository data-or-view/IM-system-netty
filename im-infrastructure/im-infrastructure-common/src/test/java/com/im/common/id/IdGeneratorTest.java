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
    void generatedIdsAreNotConstant() {
        assertNotEquals(IdGenerator.groupId(), IdGenerator.groupId());
    }

    @Test
    void blankPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> IdGenerator.next(" "));
    }
}

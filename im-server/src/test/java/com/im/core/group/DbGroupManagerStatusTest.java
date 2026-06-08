package com.im.core.group;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbGroupManagerStatusTest {

    @Test
    void groupSearchOnlyReturnsNormalGroups() {
        assertEquals(DbGroupManager.GROUP_STATUS_NORMAL, DbGroupManager.searchableGroupStatus());
    }

    @Test
    void groupOwnerCannotQuitWithoutTransferringOwnership() {
        assertFalse(DbGroupManager.canQuitGroup("owner", "owner"));
        assertTrue(DbGroupManager.canQuitGroup("owner", "member"));
    }

    @Test
    void memberCountNeverDropsBelowZeroAfterRemovingMember() {
        assertEquals(2, DbGroupManager.memberCountAfterRemove(3));
        assertEquals(0, DbGroupManager.memberCountAfterRemove(0));
    }
}

package com.im.core.group;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbGroupManagerStatusTest {

    @Test
    void groupSearchOnlyReturnsNormalGroups() {
        assertEquals(DbGroupManager.GROUP_STATUS_NORMAL, DbGroupManager.searchableGroupStatus());
    }
}

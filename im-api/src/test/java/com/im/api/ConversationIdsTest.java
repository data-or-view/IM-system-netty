package com.im.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationIdsTest {

    @Test
    void singleConversationIdIsStableRegardlessOfSenderOrder() {
        assertEquals("single_alice_bob", ConversationIds.single("alice", "bob"));
        assertEquals("single_alice_bob", ConversationIds.single("bob", "alice"));
    }

    @Test
    void singleConversationIdReturnsNullWhenEitherUserMissing() {
        assertNull(ConversationIds.single(null, "bob"));
        assertNull(ConversationIds.single("alice", null));
    }

    @Test
    void groupConversationIdUsesGroupPrefix() {
        assertEquals("group_g001", ConversationIds.group("g001"));
        assertNull(ConversationIds.group(null));
    }
}

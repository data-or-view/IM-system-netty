package com.im.core.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageStateSchemaTest {

    @Test
    void schemaSqlDefinesReadStateAndVisibilityTablesWithComments() throws Exception {
        String schema = readSchema();

        assertContainsComments(schema, "im_message_read_states");
        assertContainsComments(schema, "im_message_visibility");
        assertTrue(schema.contains("COMMENT='消息已读状态表'"));
        assertTrue(schema.contains("COMMENT='消息用户可见性表'"));
        assertTrue(schema.contains("read_seq"));
        assertTrue(schema.contains("visibility_state"));
    }

    @Test
    void messageTableDoesNotContainLegacyReadOrDeleteColumns() throws Exception {
        assertNoLegacyColumns(readSchema());
    }


    @Test
    void revokeRoleCanStoreGroupOwnerRoleCode() throws Exception {
        String schema = readSchema();

        assertTrue(schema.contains("revoke_role         SMALLINT"),
                "revoke_role must fit GroupMemberRole.OWNER code 200");
    }

    @Test
    void clientMessageIdIsUniqueOnlyInsideConversation() throws Exception {
        String schema = readSchema();

        assertTrue(schema.contains("UNIQUE KEY uk_conversation_client_msg (conversation_id, client_msg_id)"),
                "clientMsgId idempotency is scoped by conversation");
        assertTrue(!schema.contains("UNIQUE KEY uk_client_msg (client_msg_id)"),
                "clientMsgId must not be globally unique across conversations");
        assertTrue(schema.contains("INDEX idx_client_msg (client_msg_id)"),
                "clientMsgId lookup still needs a non-unique index");
    }

    @Test
    void schemaSqlDefinesSyncTablesWithComments() throws Exception {
        String schema = readSchema();

        assertContainsComments(schema, "im_sync_versions");
        assertContainsComments(schema, "im_sync_changes");
        assertTrue(schema.contains("COMMENT='增量同步版本表'"));
        assertTrue(schema.contains("COMMENT='增量同步变更日志表'"));
    }

    private static String readSchema() throws Exception {
        return Files.readString(Path.of("src/main/resources/db/schema.sql"));
    }

    private static void assertNoLegacyColumns(String ddl) {
        assertTrue(!ddl.contains(" is_read "), "im_messages must not contain legacy is_read column");
        assertTrue(!ddl.contains(" del_user_ids "), "im_messages must not contain legacy del_user_ids column");
    }

    private static void assertContainsComments(String ddl, String tableName) {
        assertTrue(ddl.contains(tableName), "missing table: " + tableName);
        assertTrue(ddl.contains(" COMMENT "), "missing column comments for: " + tableName);
    }
}

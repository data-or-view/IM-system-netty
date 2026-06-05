package com.im.core.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageStateSchemaTest {

    @Test
    void schemaSqlDefinesReadStateAndVisibilityTablesWithComments() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));

        assertContainsComments(schema, "im_message_read_states");
        assertContainsComments(schema, "im_message_visibility");
        assertTrue(schema.contains("COMMENT='消息已读状态表'"));
        assertTrue(schema.contains("COMMENT='消息用户可见性表'"));
        assertTrue(schema.contains("read_seq"));
        assertTrue(schema.contains("visibility_state"));
    }

    @Test
    void initializerDefinesReadStateAndVisibilityTablesWithComments() throws Exception {
        Method method = SchemaInitializer.class.getDeclaredMethod("getCreateTableDDL", String.class);
        method.setAccessible(true);

        String readStateDdl = (String) method.invoke(null, "im_message_read_states");
        String visibilityDdl = (String) method.invoke(null, "im_message_visibility");

        assertContainsComments(readStateDdl, "im_message_read_states");
        assertContainsComments(visibilityDdl, "im_message_visibility");
        assertTrue(readStateDdl.contains("COMMENT='消息已读状态表'"));
        assertTrue(visibilityDdl.contains("COMMENT='消息用户可见性表'"));
    }

    @Test
    void messageTableDoesNotContainLegacyReadOrDeleteColumns() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        Method method = SchemaInitializer.class.getDeclaredMethod("getCreateTableDDL", String.class);
        method.setAccessible(true);
        String initializerDdl = (String) method.invoke(null, "im_messages");

        assertNoLegacyColumns(schema);
        assertNoLegacyColumns(initializerDdl);
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

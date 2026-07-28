package com.im.bootstrap;

import com.im.common.exception.DatabasePersistenceException;
import com.im.core.db.SchemaInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL coverage for the explicit Version 2 schema lifecycle. */
class SchemaMigrationE2ETest extends BaseE2ETest {

    private static IsolatedMySqlDatabase mysql;
    private static DataSource migrationDataSource;

    @BeforeAll
    static void requireMySql() throws Exception {
        mysql = openIsolatedMySqlDatabase("im_schema_migration_e2e", false);
        migrationDataSource = mysql.dataSource();
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        mysql.reset();
    }

    @AfterAll
    static void removeDatabase() {
        if (mysql != null) {
            mysql.close();
        }
    }

    @Test
    void migrateUpgradesLegacyV11AndIsIdempotent() throws Exception {
        createLegacyV11Fixture(migrationDataSource);

        SchemaInitializer.initialize(migrationDataSource, "migrate");

        assertV2Fingerprint(migrationDataSource);
        SchemaInitializer.initialize(migrationDataSource, "migrate");
        assertEquals(1, versionTwoCount(migrationDataSource));
    }

    @Test
    void migrateBackfillsLegacyInboundEventsWithoutChangingUnreadState() throws Exception {
        createLegacyV11Fixture(migrationDataSource);
        try (Connection connection = migrationDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO im_conversations (owner_user_id, conversation_id, max_seq, unread_count) "
                    + "VALUES ('receiver', 'single_receiver_sender', 2, 1)");
            statement.executeUpdate("INSERT INTO im_messages "
                    + "(client_msg_id, server_msg_id, conversation_id, seq, send_id, recv_id, created_at) "
                    + "VALUES ('self-1', 'self-1', 'single_receiver_sender', 1, 'receiver', 'sender', 1)");
            statement.executeUpdate("INSERT INTO im_messages "
                    + "(client_msg_id, server_msg_id, conversation_id, seq, send_id, recv_id, created_at) "
                    + "VALUES ('inbound-2', 'inbound-2', 'single_receiver_sender', 2, 'sender', 'receiver', 2)");
            statement.executeUpdate("INSERT INTO im_message_read_states "
                    + "(user_id, conversation_id, read_seq, delivered_seq, unread_count, updated_at) "
                    + "VALUES ('receiver', 'single_receiver_sender', 1, 1, 1, 2)");
        }

        SchemaInitializer.initialize(migrationDataSource, "migrate");

        assertEquals(1, queryLong(migrationDataSource,
                "SELECT COUNT(*) FROM im_conversation_projection_events "
                        + "WHERE owner_user_id='receiver' AND conversation_id='single_receiver_sender'"));
        assertEquals(1, queryLong(migrationDataSource,
                "SELECT COUNT(*) FROM im_conversation_projection_events e "
                        + "LEFT JOIN im_message_read_states r "
                        + "ON r.user_id=e.owner_user_id AND r.conversation_id=e.conversation_id "
                        + "WHERE e.owner_user_id='receiver' AND e.conversation_id='single_receiver_sender' "
                        + "AND e.message_seq > COALESCE(r.read_seq, 0)"));
    }

    @Test
    void migrateDoesNotBackfillMessagesBeyondOwnersLegacyProjectionMaximum() throws Exception {
        createLegacyV11Fixture(migrationDataSource);
        try (Connection connection = migrationDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO im_conversations (owner_user_id, conversation_id, max_seq, unread_count) "
                    + "VALUES ('receiver', 'single_receiver_sender', 2, 1)");
            statement.executeUpdate("INSERT INTO im_messages "
                    + "(client_msg_id, server_msg_id, conversation_id, seq, send_id, recv_id, created_at) VALUES "
                    + "('inbound-2', 'inbound-2', 'single_receiver_sender', 2, 'sender', 'receiver', 2), "
                    + "('unprojected-3', 'unprojected-3', 'single_receiver_sender', 3, 'sender', 'receiver', 3)");
        }

        SchemaInitializer.initialize(migrationDataSource, "migrate");

        assertEquals(1, queryLong(migrationDataSource,
                "SELECT COUNT(*) FROM im_conversation_projection_events "
                        + "WHERE owner_user_id='receiver' AND conversation_id='single_receiver_sender'"));
        assertEquals(0, queryLong(migrationDataSource,
                "SELECT COUNT(*) FROM im_conversation_projection_events WHERE message_id='unprojected-3'"));
    }

    @Test
    void migrateStartsLateGroupMembersProjectionAtOwnersLegacyMinimum() throws Exception {
        createLegacyV11Fixture(migrationDataSource);
        try (Connection connection = migrationDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO im_conversations "
                    + "(owner_user_id, conversation_id, conversation_type, group_id, max_seq, unread_count) "
                    + "VALUES ('late-member', 'group_projection', 2, 'projection', 3, 1)");
            statement.executeUpdate("INSERT INTO im_seq_users "
                    + "(user_id, conversation_id, min_seq, max_seq, read_seq, updated_at) "
                    + "VALUES ('late-member', 'group_projection', 3, 3, 0, 3)");
            statement.executeUpdate("INSERT INTO im_messages "
                    + "(client_msg_id, server_msg_id, conversation_id, seq, send_id, group_id, created_at) VALUES "
                    + "('history-1', 'history-1', 'group_projection', 1, 'member-a', 'projection', 1), "
                    + "('history-2', 'history-2', 'group_projection', 2, 'member-b', 'projection', 2), "
                    + "('visible-3', 'visible-3', 'group_projection', 3, 'member-a', 'projection', 3)");
        }

        SchemaInitializer.initialize(migrationDataSource, "migrate");

        assertEquals(1, queryLong(migrationDataSource,
                "SELECT COUNT(*) FROM im_conversation_projection_events "
                        + "WHERE owner_user_id='late-member' AND conversation_id='group_projection'"));
        assertEquals(1, queryLong(migrationDataSource,
                "SELECT COUNT(*) FROM im_conversation_projection_events WHERE message_id='visible-3'"));
    }

    @Test
    void migrateResumesAfterFirstV2DdlWasApplied() throws Exception {
        createLegacyV11Fixture(migrationDataSource);
        try (Connection connection = migrationDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(firstMigrationDdl());
        }

        SchemaInitializer.initialize(migrationDataSource, "migrate");

        assertV2Fingerprint(migrationDataSource);
        assertEquals(1, versionTwoCount(migrationDataSource));
    }

    @Test
    void autoRejectsUntouchedLegacyV11WithoutMetadataChanges() throws Exception {
        createLegacyV11Fixture(migrationDataSource);
        List<String> before = metadataSnapshot(migrationDataSource);

        assertThrows(DatabasePersistenceException.class,
                () -> SchemaInitializer.initialize(migrationDataSource, "auto"));

        assertEquals(before, metadataSnapshot(migrationDataSource));
        assertFalse(tableExists(migrationDataSource, "im_schema_versions"));
    }

    @Test
    void autoCreatesAndThenValidatesBlankV2Database() throws Exception {
        SchemaInitializer.initialize(migrationDataSource, "auto");

        assertV2Fingerprint(migrationDataSource);
        SchemaInitializer.initialize(migrationDataSource, "auto");
        assertEquals(1, versionTwoCount(migrationDataSource));
    }

    @Test
    void migrateRejectsStructurallyIncompleteLegacySchemaBeforeDdl() throws Exception {
        createLegacyV11Fixture(migrationDataSource);
        try (Connection connection = migrationDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE im_users DROP COLUMN nickname");
        }
        List<String> before = metadataSnapshot(migrationDataSource);

        assertThrows(DatabasePersistenceException.class,
                () -> SchemaInitializer.initialize(migrationDataSource, "migrate"));

        assertEquals(before, metadataSnapshot(migrationDataSource));
        assertFalse(columnExists(migrationDataSource, "im_users", "password_hash"));
        assertFalse(tableExists(migrationDataSource, "im_schema_versions"));
    }

    @Test
    void migrateRejectsLegacySchemaMissingIdempotencyPrimaryKeyBeforeDdl() throws Exception {
        createLegacyV11Fixture(migrationDataSource);
        try (Connection connection = migrationDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE im_idempotency_records DROP PRIMARY KEY");
        }

        assertMigrationRejectedWithoutDdl();
    }

    @Test
    void migrateRejectsLegacySchemaMissingConversationSequenceKeyBeforeDdl() throws Exception {
        createLegacyV11Fixture(migrationDataSource);
        try (Connection connection = migrationDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE im_messages DROP INDEX uk_conversation_seq");
        }

        assertMigrationRejectedWithoutDdl();
    }

    @Test
    void autoRejectsManagedV2SchemaMissingCanonicalKey() throws Exception {
        SchemaInitializer.initialize(migrationDataSource, "auto");
        try (Connection connection = migrationDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE im_messages DROP INDEX uk_conversation_seq");
        }
        List<String> before = metadataSnapshot(migrationDataSource);

        assertThrows(DatabasePersistenceException.class,
                () -> SchemaInitializer.initialize(migrationDataSource, "auto"));

        assertEquals(before, metadataSnapshot(migrationDataSource));
        assertEquals(1, versionTwoCount(migrationDataSource));
    }

    private static void assertMigrationRejectedWithoutDdl() throws Exception {
        List<String> before = metadataSnapshot(migrationDataSource);

        assertThrows(DatabasePersistenceException.class,
                () -> SchemaInitializer.initialize(migrationDataSource, "migrate"));

        assertEquals(before, metadataSnapshot(migrationDataSource));
        assertFalse(columnExists(migrationDataSource, "im_users", "password_hash"));
        assertFalse(tableExists(migrationDataSource, "im_conversation_projection_events"));
        assertFalse(tableExists(migrationDataSource, "im_schema_versions"));
    }

    private static void assertV2Fingerprint(DataSource dataSource) throws Exception {
        assertTrue(tableExists(dataSource, "im_conversation_projection_events"));
        assertTrue(indexExists(dataSource, "im_conversation_projection_events", "uk_conversation_projection_message"));
        assertTrue(indexExists(dataSource, "im_conversation_projection_events", "idx_projection_unread"));
        assertTrue(tableExists(dataSource, "im_schema_versions"));
        assertTrue(columnExists(dataSource, "im_users", "password_hash"));
        assertEquals("SMALLINT", columnType(dataSource, "im_messages", "revoke_role"));
        assertFalse(indexExists(dataSource, "im_messages", "uk_client_msg"));
        assertTrue(indexExists(dataSource, "im_messages", "uk_conversation_client_msg"));
        assertTrue(indexExists(dataSource, "im_messages", "idx_client_msg"));
        assertEquals(1, versionTwoCount(dataSource));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT description, checksum FROM im_schema_versions WHERE version = 2")) {
            assertTrue(result.next());
            assertEquals("conversation projection events", result.getString("description"));
            String checksum = result.getString("checksum");
            assertNotNull(checksum);
            assertEquals(64, checksum.length());
            assertFalse(result.next());
        }
    }

    private static void createLegacyV11Fixture(DataSource dataSource) throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String raw : schema.split(";")) {
                String lower = raw.toLowerCase(Locale.ROOT);
                int createIndex = lower.indexOf("create table");
                if (createIndex < 0) {
                    continue;
                }
                String ddl = raw.substring(createIndex).strip();
                String ddlLower = ddl.toLowerCase(Locale.ROOT);
                if (ddlLower.contains("im_schema_versions")
                        || ddlLower.contains("im_conversation_projection_events")) {
                    continue;
                }
                statement.execute(ddl);
            }
            statement.execute("ALTER TABLE im_users DROP COLUMN password_hash");
            statement.execute("ALTER TABLE im_messages MODIFY COLUMN revoke_role TINYINT NOT NULL DEFAULT 0");
            statement.execute("ALTER TABLE im_messages DROP INDEX uk_conversation_client_msg");
            statement.execute("ALTER TABLE im_messages DROP INDEX idx_client_msg");
            statement.execute("ALTER TABLE im_messages ADD UNIQUE KEY uk_client_msg (client_msg_id)");
        }
    }

    private static String firstMigrationDdl() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/migration/V2__conversation_projection.sql"));
        for (String raw : migration.split(";")) {
            String ddl = raw.lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (left, right) -> left + "\n" + right)
                    .strip();
            if (!ddl.isEmpty()) {
                return ddl;
            }
        }
        throw new IllegalStateException("Version 2 migration resource has no DDL");
    }

    private static List<String> metadataSnapshot(DataSource dataSource) throws Exception {
        List<String> snapshot = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            List<String> tables = new ArrayList<>();
            try (ResultSet result = metadata.getTables(connection.getCatalog(), null, "im_%", new String[]{"TABLE"})) {
                while (result.next()) {
                    tables.add(result.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
            tables.sort(String::compareTo);
            for (String table : tables) {
                snapshot.add("table:" + table);
                try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, table, "%")) {
                    while (result.next()) {
                        snapshot.add("column:" + table + ":" + result.getString("COLUMN_NAME") + ":"
                                + result.getString("TYPE_NAME") + ":" + result.getInt("COLUMN_SIZE") + ":"
                                + result.getInt("NULLABLE") + ":" + result.getInt("ORDINAL_POSITION"));
                    }
                }
                try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), null, table, false, false)) {
                    while (result.next()) {
                        snapshot.add("index:" + table + ":" + result.getString("INDEX_NAME") + ":"
                                + result.getBoolean("NON_UNIQUE") + ":" + result.getString("COLUMN_NAME") + ":"
                                + result.getInt("ORDINAL_POSITION"));
                    }
                }
            }
        }
        snapshot.sort(String::compareTo);
        return snapshot;
    }

    private static int versionTwoCount(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM im_schema_versions WHERE version = 2")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static long queryLong(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static boolean tableExists(DataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getTables(
                     connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            return result.next();
        }
    }

    private static boolean columnExists(DataSource dataSource, String table, String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return result.next();
        }
    }

    private static String columnType(DataSource dataSource, String table, String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            assertTrue(result.next());
            return result.getString("TYPE_NAME").toUpperCase(Locale.ROOT);
        }
    }

    private static boolean indexExists(DataSource dataSource, String table, String index) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (result.next()) {
                if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

}

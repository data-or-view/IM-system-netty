package com.im.bootstrap;

import com.im.common.exception.DatabasePersistenceException;
import com.im.core.db.SchemaInitializer;
import com.mysql.cj.jdbc.MysqlDataSource;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real-MySQL coverage for the explicit Version 2 schema lifecycle. */
class SchemaMigrationE2ETest extends BaseE2ETest {

    private static final String DATABASE_NAME = "im_schema_migration_e2e_" + Long.toUnsignedString(System.nanoTime());
    private static MysqlDataSource adminDataSource;
    private static MysqlDataSource migrationDataSource;

    @BeforeAll
    static void requireMySql() {
        Map<String, String> config = E2ETestConfig.infrastructureDefaults();
        String configuredUrl = config.get("im.db.jdbc-url");
        adminDataSource = dataSource(withDatabase(configuredUrl, ""), config);
        migrationDataSource = dataSource(withDatabase(configuredUrl, DATABASE_NAME), config);
        try (Connection connection = adminDataSource.getConnection()) {
            recreateDatabase(connection);
        } catch (SQLException e) {
            assumeTrue(false, "Real MySQL migration prerequisite unavailable: " + e.getMessage());
        }
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        try (Connection connection = adminDataSource.getConnection()) {
            recreateDatabase(connection);
        }
    }

    @AfterAll
    static void removeDatabase() {
        if (adminDataSource == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + DATABASE_NAME + "`");
        } catch (SQLException ignored) {
            // The prerequisite failure is already reported by the test result.
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

    private static MysqlDataSource dataSource(String url, Map<String, String> config) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(url);
        dataSource.setUser(config.get("im.db.username"));
        dataSource.setPassword(config.get("im.db.password"));
        return dataSource;
    }

    private static String withDatabase(String jdbcUrl, String database) {
        int protocol = jdbcUrl.indexOf("://");
        int path = jdbcUrl.indexOf('/', protocol + 3);
        if (protocol < 0 || path < 0) {
            throw new IllegalArgumentException("Unsupported MySQL JDBC URL: " + jdbcUrl);
        }
        int query = jdbcUrl.indexOf('?', path);
        String suffix = query >= 0 ? jdbcUrl.substring(query) : "";
        return jdbcUrl.substring(0, path + 1) + database + suffix;
    }

    private static void recreateDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + DATABASE_NAME + "`");
            statement.execute("CREATE DATABASE `" + DATABASE_NAME
                    + "` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci");
        }
    }
}

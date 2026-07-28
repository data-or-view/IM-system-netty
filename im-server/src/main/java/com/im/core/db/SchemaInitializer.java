package com.im.core.db;

import com.im.common.exception.DatabasePersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Initializes, migrates, and validates the managed IM database schema. */
public final class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";
    private static final String V2_MIGRATION_RESOURCE = "/db/migration/V2__conversation_projection.sql";
    private static final int V2_VERSION = 2;
    private static final String V2_DESCRIPTION = "conversation projection events";
    private static final String MIGRATION_LOCK = "im-system-schema-migration";
    private static final int MIGRATION_LOCK_TIMEOUT_SECONDS = 60;

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-zA-Z0-9_]+)`?\\s*\\(");
    private static final Pattern MIGRATION_STEP_PATTERN = Pattern.compile(
            "(?im)^\\s*--\\s*migration-step:\\s*([a-z0-9-]+)\\s*$");
    private static final Pattern COLUMN_DEFINITION_PATTERN = Pattern.compile(
            "(?im)^\\s*`?([a-z][a-z0-9_]*)`?\\s+"
                    + "(BIGINT|INT|SMALLINT|TINYINT|MEDIUMTEXT|TEXT|VARCHAR\\(([0-9]+)\\)|CHAR\\(([0-9]+)\\))"
                    + "(?=\\s|,)([^\\r\\n]*)");
    private static final Pattern TABLE_PRIMARY_KEY_PATTERN = Pattern.compile(
            "(?im)^\\s*PRIMARY\\s+KEY\\s*\\(([^\\r\\n)]*)\\)");
    private static final Pattern NAMED_KEY_PATTERN = Pattern.compile(
            "(?im)^\\s*(UNIQUE\\s+)?(?:KEY|INDEX)\\s+`?([a-z][a-z0-9_]*)`?\\s*\\(([^\\r\\n)]*)\\)");
    private static final Pattern KEY_COLUMN_PATTERN = Pattern.compile(
            "(?i)^\\s*`?([a-z][a-z0-9_]*)`?(?:\\s*\\([0-9]+\\))?(?:\\s+(?:ASC|DESC))?\\s*$");

    private static final List<String> LEGACY_V11_TABLE_NAMES = List.of(
            "im_users", "im_friends", "im_friend_requests", "im_blacklist", "im_refresh_tokens",
            "im_groups", "im_group_members", "im_group_requests", "im_conversations", "im_messages",
            "im_message_read_states", "im_message_visibility", "im_idempotency_records",
            "im_message_send_failures", "im_sequences", "im_seq_users", "im_objects", "im_sync_versions",
            "im_sync_changes", "im_system_channels", "im_system_messages", "im_system_message_inbox");

    private static final List<String> IM_TABLE_NAMES = List.of(
            "im_schema_versions", "im_users", "im_friends", "im_friend_requests", "im_blacklist",
            "im_refresh_tokens", "im_groups", "im_group_members", "im_group_requests", "im_conversations",
            "im_messages", "im_conversation_projection_events", "im_message_read_states",
            "im_message_visibility", "im_idempotency_records", "im_message_send_failures", "im_sequences",
            "im_seq_users", "im_objects", "im_sync_versions", "im_sync_changes", "im_system_channels",
            "im_system_messages", "im_system_message_inbox");

    private static final List<String> IM_TABLE_NAMES_REVERSE = new ArrayList<>(IM_TABLE_NAMES.reversed());

    private SchemaInitializer() {
    }

    /** Initializes the schema in {@code none}, {@code auto}, {@code migrate}, or {@code rebuild} mode. */
    public static void initialize(DataSource dataSource, String mode) {
        final SchemaMode schemaMode;
        try {
            schemaMode = SchemaMode.parse(mode);
        } catch (Exception e) {
            throw new DatabasePersistenceException("Invalid database schema mode: " + mode, e);
        }
        if (schemaMode == SchemaMode.NONE) {
            log.info("Schema initialization skipped (mode=none)");
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            initializeManaged(connection, schemaMode);
        } catch (Exception e) {
            log.error("Schema initialization failed (mode={})", schemaMode.name().toLowerCase(Locale.ROOT), e);
            throw new DatabasePersistenceException("Failed to initialize database schema", e);
        }
    }

    private static void initializeManaged(Connection connection, SchemaMode mode) throws Exception {
        switch (mode) {
            case AUTO -> autoBootstrapOrValidate(connection);
            case MIGRATE -> migrateV11ToV2(connection);
            case REBUILD -> rebuildV2(connection);
            case NONE -> {
                // Handled before a connection is requested.
            }
        }
    }

    private static void autoBootstrapOrValidate(Connection connection) throws Exception {
        SchemaCatalog catalog = SchemaCatalog.inspect(connection);
        String checksum = v2Checksum();
        if (catalog.hasNoImTables()) {
            executeFreshV2Schema(connection);
            requireV2Fingerprint(connection);
            insertVersion(connection, checksum);
            log.info("Blank database initialized at managed schema Version 2");
            return;
        }
        if (!hasValidV2Version(connection, catalog, checksum)) {
            throw new IllegalStateException(
                    "existing IM schema is unmanaged; run -Dim.db.schema=migrate explicitly");
        }
        requireV2Fingerprint(connection);
        log.info("Managed schema Version 2 validated");
    }

    private static void migrateV11ToV2(Connection connection) throws Exception {
        acquireMigrationLock(connection);
        try {
            SchemaCatalog catalog = SchemaCatalog.inspect(connection);
            String checksum = v2Checksum();
            if (hasValidV2Version(connection, catalog, checksum)) {
                requireV2Fingerprint(connection);
                log.info("Managed schema Version 2 already installed");
                return;
            }

            requireRecognizedV11OrInterruptedV2(connection, catalog);
            for (MigrationStep step : loadV2MigrationSteps()) {
                applyMigrationStep(connection, step);
            }
            requireV2Fingerprint(connection);
            insertVersion(connection, checksum);
            log.info("Schema migration to Version 2 completed");
        } finally {
            releaseMigrationLock(connection);
        }
    }

    private static void rebuildV2(Connection connection) throws Exception {
        dropAllTables(connection);
        executeFreshV2Schema(connection);
        requireV2Fingerprint(connection);
        insertVersion(connection, v2Checksum());
        log.info("Schema rebuilt at managed Version 2");
    }

    private static void acquireMigrationLock(Connection connection) throws SQLException {
        String sql = "SELECT GET_LOCK('" + MIGRATION_LOCK + "', " + MIGRATION_LOCK_TIMEOUT_SECONDS + ")";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new IllegalStateException(
                        "Could not acquire database schema migration lock within "
                                + MIGRATION_LOCK_TIMEOUT_SECONDS + " seconds");
            }
        }
    }

    private static void releaseMigrationLock(Connection connection) throws SQLException {
        String sql = "SELECT RELEASE_LOCK('" + MIGRATION_LOCK + "')";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new IllegalStateException("Database schema migration lock was not released");
            }
        }
    }

    private static void requireRecognizedV11OrInterruptedV2(Connection connection, SchemaCatalog catalog)
            throws Exception {
        Set<String> missing = new LinkedHashSet<>(LEGACY_V11_TABLE_NAMES);
        missing.removeAll(catalog.imTables());
        if (!missing.isEmpty()) {
            throw incompatibleSchema("missing v1.1 tables " + missing);
        }

        Set<String> unknown = new LinkedHashSet<>(catalog.imTables());
        unknown.removeAll(new HashSet<>(IM_TABLE_NAMES));
        if (!unknown.isEmpty()) {
            throw incompatibleSchema("unknown IM tables " + unknown);
        }

        requireLegacyV11ColumnFingerprint(connection);
        Map<String, Map<String, IndexInfo>> expectedKeys = expectedKeyDefinitions();
        requireLegacyV11KeyFingerprint(connection, expectedKeys);

        if (catalog.hasTable("im_conversation_projection_events")) {
            requireProjectionTableFingerprint(connection);
            requireExactKeyFingerprint(connection, "im_conversation_projection_events",
                    expectedKeys.get("im_conversation_projection_events"), true);
        }
        if (catalog.hasTable("im_schema_versions")) {
            requireVersionTableFingerprint(connection);
            requireExactKeyFingerprint(connection, "im_schema_versions",
                    expectedKeys.get("im_schema_versions"), true);
            if (!readVersions(connection).isEmpty()) {
                throw incompatibleSchema("schema metadata exists but does not contain the expected Version 2 record");
            }
        }
    }

    private static IllegalStateException incompatibleSchema(String detail) {
        return new IllegalStateException("schema is not a recognized v1.1 or resumable Version 2 migration: " + detail);
    }

    private static void applyMigrationStep(Connection connection, MigrationStep step) throws Exception {
        boolean applied = switch (step.id()) {
            case ADD_USERS_PASSWORD_HASH -> addPasswordHashIfMissing(connection, step.sql());
            case WIDEN_MESSAGES_REVOKE_ROLE -> widenRevokeRoleIfNeeded(connection, step.sql());
            case DROP_GLOBAL_CLIENT_MSG_UNIQUE -> dropGlobalClientMessageUniqueIfPresent(connection, step.sql());
            case ADD_CONVERSATION_CLIENT_MSG_UNIQUE -> addIndexIfMissing(connection, "im_messages",
                    "uk_conversation_client_msg", false, List.of("conversation_id", "client_msg_id"), step.sql());
            case ADD_CLIENT_MSG_LOOKUP -> addIndexIfMissing(connection, "im_messages", "idx_client_msg", true,
                    List.of("client_msg_id"), step.sql());
            case CREATE_CONVERSATION_PROJECTION_EVENTS -> createTableIfMissing(connection,
                    "im_conversation_projection_events", step.sql());
            case CREATE_SCHEMA_VERSIONS -> createTableIfMissing(connection, "im_schema_versions", step.sql());
        };
        if (applied) {
            log.info("Applied Version 2 migration step: {}", step.id().resourceId());
        }
    }

    private static boolean addPasswordHashIfMissing(Connection connection, String sql) throws Exception {
        ColumnInfo existing = column(connection, "im_users", "password_hash");
        if (existing == null) {
            execute(connection, sql);
            return true;
        }
        if (!existing.matches("varchar", 255, false)) {
            throw incompatibleSchema("incompatible im_users.password_hash");
        }
        return false;
    }

    private static boolean widenRevokeRoleIfNeeded(Connection connection, String sql) throws Exception {
        ColumnInfo existing = requireColumn(connection, "im_messages", "revoke_role");
        if (existing.typeIs("smallint")) {
            return false;
        }
        if (!existing.typeIs("tinyint")) {
            throw incompatibleSchema("incompatible im_messages.revoke_role type " + existing.typeName());
        }
        execute(connection, sql);
        return true;
    }

    private static boolean dropGlobalClientMessageUniqueIfPresent(Connection connection, String sql) throws Exception {
        IndexInfo existing = indexes(connection, "im_messages").get("uk_client_msg");
        if (existing == null) {
            return false;
        }
        if (existing.nonUnique() || !existing.columns().equals(List.of("client_msg_id"))) {
            throw incompatibleSchema("incompatible im_messages.uk_client_msg");
        }
        execute(connection, sql);
        return true;
    }

    private static boolean addIndexIfMissing(Connection connection, String table, String indexName,
                                             boolean nonUnique, List<String> columns, String sql) throws Exception {
        IndexInfo existing = indexes(connection, table).get(indexName.toLowerCase(Locale.ROOT));
        if (existing == null) {
            execute(connection, sql);
            return true;
        }
        if (existing.nonUnique() != nonUnique || !existing.columns().equals(columns)) {
            throw incompatibleSchema("incompatible " + table + "." + indexName);
        }
        return false;
    }

    private static boolean createTableIfMissing(Connection connection, String table, String sql) throws Exception {
        if (tableExists(connection, table)) {
            return false;
        }
        execute(connection, sql);
        return true;
    }

    private static void executeFreshV2Schema(Connection connection) throws Exception {
        Map<String, String> createTableSql = loadCreateTableSql();
        try (Statement statement = connection.createStatement()) {
            for (String table : IM_TABLE_NAMES) {
                String ddl = createTableSql.get(table);
                if (ddl == null) {
                    throw new IllegalStateException(
                            "Missing CREATE TABLE statement for " + table + " in " + SCHEMA_RESOURCE);
                }
                statement.execute(ddl);
            }
        }
    }

    private static void dropAllTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String table : IM_TABLE_NAMES_REVERSE) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    private static void requireV2Fingerprint(Connection connection) throws Exception {
        SchemaCatalog catalog = SchemaCatalog.inspect(connection);
        Set<String> missing = new LinkedHashSet<>(IM_TABLE_NAMES);
        missing.removeAll(catalog.imTables());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Version 2 fingerprint mismatch: missing tables " + missing);
        }

        Map<String, Map<String, ExpectedColumn>> expectedByTable = expectedColumnDefinitions();
        Map<String, Map<String, IndexInfo>> expectedKeysByTable = expectedKeyDefinitions();
        for (String table : IM_TABLE_NAMES) {
            requireTableColumnFingerprint(connection, table, expectedByTable.get(table));
            requireExactKeyFingerprint(connection, table, expectedKeysByTable.get(table), false);
        }

        ColumnInfo passwordHash = requireColumn(connection, "im_users", "password_hash");
        if (!passwordHash.matches("varchar", 255, false)) {
            throw new IllegalStateException("Version 2 fingerprint mismatch: im_users.password_hash");
        }
        ColumnInfo revokeRole = requireColumn(connection, "im_messages", "revoke_role");
        if (!revokeRole.typeIs("smallint")) {
            throw new IllegalStateException("Version 2 fingerprint mismatch: im_messages.revoke_role");
        }
        requireIndex(connection, "im_messages", "uk_conversation_client_msg", false,
                List.of("conversation_id", "client_msg_id"));
        requireIndex(connection, "im_messages", "idx_client_msg", true, List.of("client_msg_id"));
        for (IndexInfo index : indexes(connection, "im_messages").values()) {
            if (!index.nonUnique() && index.columns().equals(List.of("client_msg_id"))) {
                throw new IllegalStateException(
                        "Version 2 fingerprint mismatch: global client_msg_id unique index " + index.name());
            }
        }
        requireProjectionTableFingerprint(connection);
        requireVersionTableFingerprint(connection);
    }

    private static void requireProjectionTableFingerprint(Connection connection) throws Exception {
        requireColumnMatch(connection, "im_conversation_projection_events", "owner_user_id", "varchar", 64, false);
        requireColumnMatch(connection, "im_conversation_projection_events", "conversation_id", "varchar", 128, false);
        requireColumnMatch(connection, "im_conversation_projection_events", "message_id", "varchar", 128, false);
        requireColumnType(connection, "im_conversation_projection_events", "message_seq", "bigint", false);
        requireColumnType(connection, "im_conversation_projection_events", "created_at", "bigint", false);
        requirePrimaryKey(connection, "im_conversation_projection_events",
                List.of("owner_user_id", "conversation_id", "message_id"));
        requireIndex(connection, "im_conversation_projection_events", "uk_conversation_projection_message", false,
                List.of("owner_user_id", "conversation_id", "message_id"));
        requireIndex(connection, "im_conversation_projection_events", "idx_projection_unread", true,
                List.of("owner_user_id", "conversation_id", "message_seq"));
    }

    private static void requireVersionTableFingerprint(Connection connection) throws Exception {
        requireColumnType(connection, "im_schema_versions", "version", "int", false);
        requireColumnMatch(connection, "im_schema_versions", "description", "varchar", 255, false);
        requireColumnMatch(connection, "im_schema_versions", "checksum", "char", 64, false);
        requireColumnType(connection, "im_schema_versions", "installed_at", "bigint", false);
        requirePrimaryKey(connection, "im_schema_versions", List.of("version"));
    }

    private static void requireLegacyV11ColumnFingerprint(Connection connection) throws Exception {
        Map<String, Map<String, ExpectedColumn>> expectedByTable = expectedColumnDefinitions();
        for (String table : LEGACY_V11_TABLE_NAMES) {
            Map<String, ExpectedColumn> expected = expectedByTable.get(table);
            Map<String, ColumnInfo> actual = columns(connection, table);
            if (expected == null || actual.isEmpty()) {
                throw incompatibleSchema("missing column definitions for " + table);
            }

            Set<String> unknown = new LinkedHashSet<>(actual.keySet());
            unknown.removeAll(expected.keySet());
            if (!unknown.isEmpty()) {
                throw incompatibleSchema("unknown columns on " + table + ": " + unknown);
            }

            for (Map.Entry<String, ExpectedColumn> entry : expected.entrySet()) {
                String columnName = entry.getKey();
                ColumnInfo actualColumn = actual.get(columnName);
                if ("im_users".equals(table) && "password_hash".equals(columnName) && actualColumn == null) {
                    continue;
                }
                if (actualColumn == null) {
                    throw incompatibleSchema("missing column " + table + "." + columnName);
                }
                if ("im_messages".equals(table) && "revoke_role".equals(columnName)
                        && actualColumn.typeIs("tinyint") && !actualColumn.nullable()) {
                    continue;
                }
                if (!entry.getValue().matches(actualColumn)) {
                    throw incompatibleSchema("incompatible column " + table + "." + columnName);
                }
            }
        }
    }

    private static void requireLegacyV11KeyFingerprint(
            Connection connection, Map<String, Map<String, IndexInfo>> expectedByTable) throws Exception {
        for (String table : LEGACY_V11_TABLE_NAMES) {
            Map<String, IndexInfo> expected = expectedByTable.get(table);
            if ("im_messages".equals(table)) {
                requireMessageMigrationKeyPrefix(connection, expected);
            } else {
                requireExactKeyFingerprint(connection, table, expected, true);
            }
        }
    }

    private static void requireMessageMigrationKeyPrefix(
            Connection connection, Map<String, IndexInfo> expectedV2) throws Exception {
        if (expectedV2 == null) {
            throw incompatibleSchema("missing canonical key definitions for im_messages");
        }

        Map<String, IndexInfo> stableExpected = new LinkedHashMap<>(expectedV2);
        IndexInfo expectedConversationUnique = stableExpected.remove("uk_conversation_client_msg");
        IndexInfo expectedClientLookup = stableExpected.remove("idx_client_msg");

        Map<String, IndexInfo> stableActual = new LinkedHashMap<>(indexes(connection, "im_messages"));
        IndexInfo globalUnique = stableActual.remove("uk_client_msg");
        IndexInfo conversationUnique = stableActual.remove("uk_conversation_client_msg");
        IndexInfo clientLookup = stableActual.remove("idx_client_msg");

        if (!keyMapsMatch(stableExpected, stableActual)) {
            throw incompatibleSchema("key fingerprint mismatch for im_messages");
        }
        if (globalUnique != null && !keyMatches(globalUnique,
                new IndexInfo("uk_client_msg", false, List.of("client_msg_id")))) {
            throw incompatibleSchema("incompatible im_messages.uk_client_msg");
        }
        if (conversationUnique != null && !keyMatches(conversationUnique, expectedConversationUnique)) {
            throw incompatibleSchema("incompatible im_messages.uk_conversation_client_msg");
        }
        if (clientLookup != null && !keyMatches(clientLookup, expectedClientLookup)) {
            throw incompatibleSchema("incompatible im_messages.idx_client_msg");
        }

        Set<String> prefixState = new LinkedHashSet<>();
        if (globalUnique != null) prefixState.add("uk_client_msg");
        if (conversationUnique != null) prefixState.add("uk_conversation_client_msg");
        if (clientLookup != null) prefixState.add("idx_client_msg");
        if (!Set.of(
                Set.of("uk_client_msg"),
                Set.<String>of(),
                Set.of("uk_conversation_client_msg"),
                Set.of("uk_conversation_client_msg", "idx_client_msg")
        ).contains(Set.copyOf(prefixState))) {
            throw incompatibleSchema("invalid im_messages key migration prefix " + prefixState);
        }
    }

    private static void requireExactKeyFingerprint(Connection connection, String table,
                                                   Map<String, IndexInfo> expected, boolean legacy)
            throws Exception {
        Map<String, IndexInfo> actual = indexes(connection, table);
        if (expected == null || !keyMapsMatch(expected, actual)) {
            String detail = "key fingerprint mismatch for " + table;
            if (legacy) {
                throw incompatibleSchema(detail);
            }
            throw new IllegalStateException("Version 2 fingerprint mismatch: " + table + " keys");
        }
    }

    private static boolean keyMapsMatch(Map<String, IndexInfo> expected, Map<String, IndexInfo> actual) {
        if (!expected.keySet().equals(actual.keySet())) {
            return false;
        }
        for (Map.Entry<String, IndexInfo> entry : expected.entrySet()) {
            if (!keyMatches(actual.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean keyMatches(IndexInfo actual, IndexInfo expected) {
        return actual != null && expected != null
                && actual.nonUnique() == expected.nonUnique()
                && actual.columns().equals(expected.columns());
    }

    private static void requireTableColumnFingerprint(Connection connection, String table,
                                                      Map<String, ExpectedColumn> expected) throws Exception {
        Map<String, ColumnInfo> actual = columns(connection, table);
        if (expected == null || !expected.keySet().equals(actual.keySet())) {
            throw new IllegalStateException("Version 2 fingerprint mismatch: " + table + " columns");
        }
        for (Map.Entry<String, ExpectedColumn> entry : expected.entrySet()) {
            if (!entry.getValue().matches(actual.get(entry.getKey()))) {
                throw new IllegalStateException(
                        "Version 2 fingerprint mismatch: " + table + "." + entry.getKey());
            }
        }
    }

    private static void requireColumnMatch(Connection connection, String table, String column, String type,
                                           int size, boolean nullable) throws Exception {
        ColumnInfo actual = requireColumn(connection, table, column);
        if (!actual.matches(type, size, nullable)) {
            throw new IllegalStateException("Version 2 fingerprint mismatch: " + table + "." + column);
        }
    }

    private static void requireColumnType(Connection connection, String table, String column, String type,
                                          boolean nullable) throws Exception {
        ColumnInfo actual = requireColumn(connection, table, column);
        if (!actual.typeIs(type) || actual.nullable() != nullable) {
            throw new IllegalStateException("Version 2 fingerprint mismatch: " + table + "." + column);
        }
    }

    private static ColumnInfo requireColumn(Connection connection, String table, String column) throws Exception {
        ColumnInfo result = column(connection, table, column);
        if (result == null) {
            throw new IllegalStateException("Required schema column is missing: " + table + "." + column);
        }
        return result;
    }

    private static void requireIndex(Connection connection, String table, String indexName,
                                     boolean nonUnique, List<String> columns) throws Exception {
        IndexInfo actual = indexes(connection, table).get(indexName.toLowerCase(Locale.ROOT));
        if (actual == null || actual.nonUnique() != nonUnique || !actual.columns().equals(columns)) {
            throw new IllegalStateException("Version 2 fingerprint mismatch: " + table + "." + indexName);
        }
    }

    private static void requirePrimaryKey(Connection connection, String table, List<String> columns)
            throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        List<OrderedColumn> actual = new ArrayList<>();
        try (ResultSet result = metadata.getPrimaryKeys(connection.getCatalog(), null, table)) {
            while (result.next()) {
                actual.add(new OrderedColumn(result.getShort("KEY_SEQ"),
                        result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT)));
            }
        }
        List<String> actualColumns = actual.stream()
                .sorted(Comparator.comparingInt(OrderedColumn::position))
                .map(OrderedColumn::name)
                .toList();
        if (!actualColumns.equals(columns)) {
            throw new IllegalStateException("Version 2 fingerprint mismatch: " + table + " primary key");
        }
    }

    private static boolean hasValidV2Version(Connection connection, SchemaCatalog catalog, String checksum)
            throws Exception {
        if (!catalog.hasTable("im_schema_versions")) {
            return false;
        }
        try {
            requireVersionTableFingerprint(connection);
        } catch (IllegalStateException e) {
            return false;
        }
        List<SchemaVersion> versions = readVersions(connection);
        return versions.size() == 1
                && versions.getFirst().version() == V2_VERSION
                && V2_DESCRIPTION.equals(versions.getFirst().description())
                && checksum.equals(versions.getFirst().checksum());
    }

    private static List<SchemaVersion> readVersions(Connection connection) throws SQLException {
        List<SchemaVersion> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT version, description, checksum FROM im_schema_versions ORDER BY version")) {
            while (result.next()) {
                versions.add(new SchemaVersion(
                        result.getInt("version"), result.getString("description"), result.getString("checksum")));
            }
        }
        return versions;
    }

    private static void insertVersion(Connection connection, String checksum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO im_schema_versions (version, description, checksum, installed_at) VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, V2_VERSION);
            statement.setString(2, V2_DESCRIPTION);
            statement.setString(3, checksum);
            statement.setLong(4, System.currentTimeMillis());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Version 2 metadata record was not inserted");
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            return result.next();
        }
    }

    private static ColumnInfo column(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, table, column)) {
            if (!result.next()) {
                return null;
            }
            return new ColumnInfo(
                    result.getString("TYPE_NAME").toLowerCase(Locale.ROOT),
                    result.getInt("COLUMN_SIZE"),
                    result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
        }
    }

    private static Map<String, ColumnInfo> columns(Connection connection, String table) throws SQLException {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, table, "%")) {
            while (result.next()) {
                columns.put(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), new ColumnInfo(
                        result.getString("TYPE_NAME").toLowerCase(Locale.ROOT),
                        result.getInt("COLUMN_SIZE"),
                        result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
            }
        }
        return columns;
    }

    private static Map<String, IndexInfo> indexes(Connection connection, String table) throws SQLException {
        Map<String, MutableIndex> collected = new LinkedHashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                String columnName = result.getString("COLUMN_NAME");
                if (name == null || columnName == null) {
                    continue;
                }
                String key = name.toLowerCase(Locale.ROOT);
                boolean nonUnique = result.getBoolean("NON_UNIQUE");
                MutableIndex index = collected.computeIfAbsent(key,
                        ignored -> new MutableIndex(name, nonUnique, new ArrayList<>()));
                index.columns().add(new OrderedColumn(
                        result.getShort("ORDINAL_POSITION"), columnName.toLowerCase(Locale.ROOT)));
            }
        }

        Map<String, IndexInfo> result = new LinkedHashMap<>();
        for (Map.Entry<String, MutableIndex> entry : collected.entrySet()) {
            MutableIndex index = entry.getValue();
            List<String> columns = index.columns().stream()
                    .sorted(Comparator.comparingInt(OrderedColumn::position))
                    .map(OrderedColumn::name)
                    .toList();
            result.put(entry.getKey(), new IndexInfo(index.name(), index.nonUnique(), columns));
        }
        return result;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Map<String, String> loadCreateTableSql() throws Exception {
        String schemaSql = readResource(SCHEMA_RESOURCE);
        Map<String, String> statements = new HashMap<>();
        for (String rawStatement : schemaSql.split(";")) {
            String statement = rawStatement.strip();
            int createIndex = findCreateTableIndex(statement);
            if (createIndex < 0) {
                continue;
            }
            String createStatement = statement.substring(createIndex).strip();
            Matcher matcher = CREATE_TABLE_PATTERN.matcher(createStatement);
            if (matcher.find()) {
                statements.put(matcher.group(1).toLowerCase(Locale.ROOT), createStatement);
            }
        }
        return statements;
    }

    private static List<MigrationStep> loadV2MigrationSteps() throws Exception {
        String migrationSql = readResource(V2_MIGRATION_RESOURCE);
        List<MigrationStep> steps = new ArrayList<>();
        for (String rawStatement : migrationSql.split(";")) {
            String sql = rawStatement.lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"))
                    .strip();
            if (sql.isEmpty()) {
                continue;
            }
            Matcher matcher = MIGRATION_STEP_PATTERN.matcher(rawStatement);
            if (!matcher.find()) {
                throw new IllegalStateException("Migration statement lacks a migration-step marker: " + sql);
            }
            steps.add(new MigrationStep(MigrationStepId.fromResourceId(matcher.group(1)), sql));
        }
        List<MigrationStepId> actualOrder = steps.stream().map(MigrationStep::id).toList();
        List<MigrationStepId> expectedOrder = List.of(MigrationStepId.values());
        if (!actualOrder.equals(expectedOrder)) {
            throw new IllegalStateException(
                    "Version 2 migration steps are missing, duplicated, or out of order: " + actualOrder);
        }
        return steps;
    }

    private static Map<String, Map<String, ExpectedColumn>> expectedColumnDefinitions() throws Exception {
        Map<String, Map<String, ExpectedColumn>> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, String> table : loadCreateTableSql().entrySet()) {
            Map<String, ExpectedColumn> columns = new LinkedHashMap<>();
            Matcher matcher = COLUMN_DEFINITION_PATTERN.matcher(table.getValue());
            while (matcher.find()) {
                String rawType = matcher.group(2).toLowerCase(Locale.ROOT);
                String type = rawType.startsWith("varchar") ? "varchar"
                        : rawType.startsWith("char") ? "char" : rawType;
                int size = matcher.group(3) != null ? Integer.parseInt(matcher.group(3))
                        : matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 0;
                String modifiers = matcher.group(5).toUpperCase(Locale.ROOT);
                boolean nullable = !modifiers.contains("NOT NULL") && !modifiers.contains("PRIMARY KEY");
                columns.put(matcher.group(1).toLowerCase(Locale.ROOT),
                        new ExpectedColumn(type, size, nullable));
            }
            if (columns.isEmpty()) {
                throw new IllegalStateException("No column definitions parsed for " + table.getKey());
            }
            definitions.put(table.getKey(), Map.copyOf(columns));
        }
        return Map.copyOf(definitions);
    }

    private static Map<String, Map<String, IndexInfo>> expectedKeyDefinitions() throws Exception {
        Map<String, Map<String, IndexInfo>> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, String> table : loadCreateTableSql().entrySet()) {
            String tableName = table.getKey();
            String createSql = table.getValue();
            Map<String, IndexInfo> keys = new LinkedHashMap<>();

            Matcher columnMatcher = COLUMN_DEFINITION_PATTERN.matcher(createSql);
            while (columnMatcher.find()) {
                if (columnMatcher.group(5).toUpperCase(Locale.ROOT).contains("PRIMARY KEY")) {
                    addExpectedKey(keys, tableName,
                            new IndexInfo("PRIMARY", false,
                                    List.of(columnMatcher.group(1).toLowerCase(Locale.ROOT))));
                }
            }

            Matcher primaryMatcher = TABLE_PRIMARY_KEY_PATTERN.matcher(createSql);
            while (primaryMatcher.find()) {
                addExpectedKey(keys, tableName,
                        new IndexInfo("PRIMARY", false,
                                parseKeyColumns(tableName, "PRIMARY", primaryMatcher.group(1))));
            }

            Matcher namedMatcher = NAMED_KEY_PATTERN.matcher(createSql);
            while (namedMatcher.find()) {
                String keyName = namedMatcher.group(2);
                addExpectedKey(keys, tableName,
                        new IndexInfo(keyName, namedMatcher.group(1) == null,
                                parseKeyColumns(tableName, keyName, namedMatcher.group(3))));
            }

            if (keys.isEmpty()) {
                throw new IllegalStateException("No key definitions parsed for " + tableName);
            }
            definitions.put(tableName, Map.copyOf(keys));
        }
        return Map.copyOf(definitions);
    }

    private static void addExpectedKey(Map<String, IndexInfo> keys, String table, IndexInfo key) {
        String normalizedName = key.name().toLowerCase(Locale.ROOT);
        if (keys.putIfAbsent(normalizedName, key) != null) {
            throw new IllegalStateException("Duplicate key definition in " + table + ": " + key.name());
        }
    }

    private static List<String> parseKeyColumns(String table, String key, String definition) {
        List<String> columns = new ArrayList<>();
        for (String rawColumn : definition.split(",")) {
            Matcher matcher = KEY_COLUMN_PATTERN.matcher(rawColumn);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "Unsupported key column definition in " + table + "." + key + ": " + rawColumn.strip());
            }
            columns.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("No key columns parsed for " + table + "." + key);
        }
        return List.copyOf(columns);
    }

    private static String v2Checksum() throws Exception {
        byte[] migration = readResource(V2_MIGRATION_RESOURCE).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration));
    }

    private static String readResource(String resource) throws Exception {
        try (InputStream input = SchemaInitializer.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Schema resource not found: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int findCreateTableIndex(String statement) {
        return statement.toLowerCase(Locale.ROOT).indexOf("create table");
    }

    private enum SchemaMode {
        NONE, AUTO, MIGRATE, REBUILD;

        static SchemaMode parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private enum MigrationStepId {
        ADD_USERS_PASSWORD_HASH("add-users-password-hash"),
        WIDEN_MESSAGES_REVOKE_ROLE("widen-messages-revoke-role"),
        DROP_GLOBAL_CLIENT_MSG_UNIQUE("drop-global-client-msg-unique"),
        ADD_CONVERSATION_CLIENT_MSG_UNIQUE("add-conversation-client-msg-unique"),
        ADD_CLIENT_MSG_LOOKUP("add-client-msg-lookup"),
        CREATE_CONVERSATION_PROJECTION_EVENTS("create-conversation-projection-events"),
        CREATE_SCHEMA_VERSIONS("create-schema-versions");

        private final String resourceId;

        MigrationStepId(String resourceId) {
            this.resourceId = resourceId;
        }

        String resourceId() {
            return resourceId;
        }

        static MigrationStepId fromResourceId(String resourceId) {
            for (MigrationStepId value : values()) {
                if (value.resourceId.equals(resourceId)) {
                    return value;
                }
            }
            throw new IllegalStateException("Unknown Version 2 migration step: " + resourceId);
        }
    }

    private record MigrationStep(MigrationStepId id, String sql) {
    }

    private record SchemaVersion(int version, String description, String checksum) {
    }

    private record ColumnInfo(String typeName, int size, boolean nullable) {
        boolean typeIs(String expected) {
            return expected.equalsIgnoreCase(typeName);
        }

        boolean matches(String expectedType, int expectedSize, boolean expectedNullable) {
            return typeIs(expectedType) && size == expectedSize && nullable == expectedNullable;
        }
    }

    private record ExpectedColumn(String typeName, int size, boolean nullable) {
        boolean matches(ColumnInfo actual) {
            return actual != null
                    && typeName.equalsIgnoreCase(actual.typeName())
                    && (size == 0 || size == actual.size())
                    && nullable == actual.nullable();
        }
    }

    private record OrderedColumn(int position, String name) {
    }

    private record MutableIndex(String name, boolean nonUnique, List<OrderedColumn> columns) {
    }

    private record IndexInfo(String name, boolean nonUnique, List<String> columns) {
    }

    private record SchemaCatalog(Set<String> imTables) {
        static SchemaCatalog inspect(Connection connection) throws SQLException {
            Set<String> tables = new LinkedHashSet<>();
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet result = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (result.next()) {
                    String table = result.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                    if (table.startsWith("im_")) {
                        tables.add(table);
                    }
                }
            }
            return new SchemaCatalog(Set.copyOf(tables));
        }

        boolean hasNoImTables() {
            return imTables.isEmpty();
        }

        boolean hasTable(String table) {
            return imTables.contains(table.toLowerCase(Locale.ROOT));
        }
    }
}

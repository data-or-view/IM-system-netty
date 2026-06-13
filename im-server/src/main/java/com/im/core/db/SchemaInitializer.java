package com.im.core.db;

import com.im.common.exception.DatabasePersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库 Schema 初始化器。
 *
 * <p>在启动时检查表是否存在，按需自动建表。支持三种模式：</p>
 * <ul>
 *   <li>{@code none} — 跳过初始化</li>
 *   <li>{@code auto} — 表不存在时创建（默认）</li>
 *   <li>{@code rebuild} — 先删后建</li>
 * </ul>
 *
 * <p>配置方式（application.yml）：</p>
 * <pre>{@code
 * im:
 *   db:
 *     schema: auto   # none | auto | rebuild
 * }</pre>
 */
public final class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-zA-Z0-9_]+)`?\\s*\\(");

    private SchemaInitializer() {}

    /** 所有表名（与业务依赖顺序一致，rebuild 时反向遍历以处理外键依赖） */
    private static final List<String> TABLE_NAMES = List.of(
            "im_users",
            "im_blacklist",
            "im_friends",
            "im_friend_requests",
            "im_groups",
            "im_group_members",
            "im_group_requests",
            "im_conversations",
            "im_messages",
            "im_message_read_states",
            "im_message_visibility",
            "im_idempotency_records",
            "im_objects",
            "im_sequences",
            "im_seq_users",
            "im_sync_versions",
            "im_sync_changes",
            "im_system_channels",
            "im_system_messages",
            "im_system_message_inbox"
    );

    /** 逆序（rebuild 时先删子表） */
    private static final List<String> TABLE_NAMES_REVERSE = new ArrayList<>(TABLE_NAMES.reversed());

    /**
     * 初始化数据库 Schema。
     *
     * @param dataSource 数据源
     * @param mode       初始化模式：none / auto / rebuild
     */
    public static void initialize(DataSource dataSource, String mode) {
        if (mode == null || "none".equalsIgnoreCase(mode)) {
            log.info("Schema initialization skipped (mode=none)");
            return;
        }

        boolean rebuild = "rebuild".equalsIgnoreCase(mode);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            if (rebuild) {
                dropAllTables(stmt);
                log.info("All tables dropped (schema=rebuild)");
            }

            List<String> missing = rebuild ? TABLE_NAMES : findMissingTables(conn);
            if (!rebuild) {
                applyLightweightMigrations(conn, stmt);
            }
            if (missing.isEmpty()) {
                log.info("All tables already exist, skipping");
                return;
            }

            createTables(stmt, missing);
            log.info("Schema initialized: {} tables created/migrated ({})",
                    missing.size(), String.join(", ", missing));
        } catch (Exception e) {
            log.error("Schema initialization failed", e);
            throw new DatabasePersistenceException("Failed to initialize database schema", e);
        }
    }

    // ── 建表（按依赖顺序） ──

    private static void createTables(Statement stmt, List<String> tables) throws Exception {
        Map<String, String> createTableSql = loadCreateTableSql();
        for (String table : tables) {
            String ddl = createTableSql.get(table);
            if (ddl == null) {
                throw new IllegalStateException("Missing CREATE TABLE statement for " + table + " in " + SCHEMA_RESOURCE);
            }
            stmt.execute(ddl);
            log.debug("Created table: {}", table);
        }
    }

    private static void dropAllTables(Statement stmt) throws Exception {
        // MySQL 的外键校验是连接级开关；rebuild 只在开发/测试使用，优先保证清表顺序不被历史外键卡住。
        stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : TABLE_NAMES_REVERSE) {
            stmt.execute("DROP TABLE IF EXISTS " + table);
        }
        stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    // ── 表存在性检查 ──

    private static List<String> findMissingTables(Connection conn) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        List<String> missing = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            java.util.Set<String> existing = new java.util.HashSet<>();
            while (rs.next()) {
                existing.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
            for (String table : TABLE_NAMES) {
                if (!existing.contains(table)) {
                    missing.add(table);
                }
            }
        }
        return missing;
    }

    private static void applyLightweightMigrations(Connection conn, Statement stmt) throws Exception {
        ensureColumn(conn, stmt, "im_users", "password_hash",
                "ALTER TABLE im_users ADD COLUMN password_hash VARCHAR(255) NOT NULL DEFAULT '' AFTER global_recv_msg_opt");
    }

    private static void ensureColumn(Connection conn, Statement stmt, String table, String column, String ddl) throws Exception {
        if (tableExists(conn, table) && !columnExists(conn, table, column)) {
            stmt.execute(ddl);
            log.info("Schema migrated: added {}.{}", table, column);
        }
    }

    private static boolean tableExists(Connection conn, String table) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private static Map<String, String> loadCreateTableSql() throws Exception {
        String schemaSql = readSchemaSql();
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

    private static String readSchemaSql() throws Exception {
        try (InputStream input = SchemaInitializer.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Schema resource not found: " + SCHEMA_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int findCreateTableIndex(String statement) {
        String lower = statement.toLowerCase(Locale.ROOT);
        return lower.indexOf("create table");
    }
}

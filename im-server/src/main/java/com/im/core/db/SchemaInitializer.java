package com.im.core.db;

import com.im.common.exception.DatabasePersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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

    private SchemaInitializer() {}

    /** 所有表名（与 DDL 顺序一致，rebuild 时反向遍历以处理外键依赖） */
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
            "im_objects",
            "im_sequences",
            "im_seq_users",
            "im_sync_versions",
            "im_sync_changes"
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
        for (String table : tables) {
            String ddl = getCreateTableDDL(table);
            if (ddl != null) {
                stmt.execute(ddl);
                log.debug("Created table: {}", table);
            }
        }
    }

    private static void dropAllTables(Statement stmt) throws Exception {
        // 关闭外键检查，避免删表顺序失败
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
                existing.add(rs.getString("TABLE_NAME").toLowerCase());
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

    // ── DDL ──

    private static String getCreateTableDDL(String table) {
        return switch (table) {
            case "im_users" -> """
                    CREATE TABLE im_users (
                        user_id         VARCHAR(64)     NOT NULL PRIMARY KEY,
                        nickname        VARCHAR(255)    NOT NULL DEFAULT '',
                        face_url        VARCHAR(512)    NOT NULL DEFAULT '',
                        ex              VARCHAR(1024)   NOT NULL DEFAULT '',
                        app_manger_level INT            NOT NULL DEFAULT 0,
                        global_recv_msg_opt INT         NOT NULL DEFAULT 0,
                        password_hash   VARCHAR(255)    NOT NULL DEFAULT '',
                        status          INT             NOT NULL DEFAULT 1,
                        created_at      BIGINT          NOT NULL DEFAULT 0,
                        updated_at      BIGINT          NOT NULL DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_friends" -> """
                    CREATE TABLE im_friends (
                        id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        owner_user_id   VARCHAR(64)     NOT NULL,
                        friend_user_id  VARCHAR(64)     NOT NULL,
                        remark          VARCHAR(255)    NOT NULL DEFAULT '',
                        add_source      INT             NOT NULL DEFAULT 0,
                        operator_user_id VARCHAR(64)    NOT NULL DEFAULT '',
                        ex              VARCHAR(1024)   NOT NULL DEFAULT '',
                        is_pinned       INT             NOT NULL DEFAULT 0,
                        created_at      BIGINT          NOT NULL DEFAULT 0,
                        UNIQUE KEY uk_friend (owner_user_id, friend_user_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_friend_requests" -> """
                    CREATE TABLE im_friend_requests (
                        id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        from_user_id    VARCHAR(64)     NOT NULL,
                        to_user_id      VARCHAR(64)     NOT NULL,
                        handle_result   INT             NOT NULL DEFAULT 0,
                        req_msg         VARCHAR(512)    NOT NULL DEFAULT '',
                        handler_user_id VARCHAR(64)     NOT NULL DEFAULT '',
                        handle_msg      VARCHAR(512)    NOT NULL DEFAULT '',
                        handle_time     BIGINT          NOT NULL DEFAULT 0,
                        ex              VARCHAR(1024)   NOT NULL DEFAULT '',
                        created_at      BIGINT          NOT NULL DEFAULT 0,
                        KEY idx_to_user (to_user_id, handle_result)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_blacklist" -> """
                    CREATE TABLE im_blacklist (
                        id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        owner_user_id   VARCHAR(64)     NOT NULL,
                        block_user_id   VARCHAR(64)     NOT NULL,
                        add_source      INT             NOT NULL DEFAULT 0,
                        operator_user_id VARCHAR(64)    NOT NULL DEFAULT '',
                        ex              VARCHAR(1024)   NOT NULL DEFAULT '',
                        created_at      BIGINT          NOT NULL DEFAULT 0,
                        UNIQUE KEY uk_block (owner_user_id, block_user_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_groups" -> """
                    CREATE TABLE im_groups (
                        group_id         VARCHAR(64)    NOT NULL PRIMARY KEY,
                        group_name       VARCHAR(255)   NOT NULL DEFAULT '',
                        notification     TEXT,
                        introduction     VARCHAR(512)   NOT NULL DEFAULT '',
                        face_url         VARCHAR(512)   NOT NULL DEFAULT '',
                        owner_user_id    VARCHAR(64)    NOT NULL DEFAULT '',
                        member_count     INT            NOT NULL DEFAULT 0,
                        status           INT            NOT NULL DEFAULT 1,
                        group_type       INT            NOT NULL DEFAULT 0,
                        need_verification INT           NOT NULL DEFAULT 0,
                        look_member_info  INT           NOT NULL DEFAULT 0,
                        apply_member_friend INT         NOT NULL DEFAULT 0,
                        notification_user_id VARCHAR(64) NOT NULL DEFAULT '',
                        notification_time  BIGINT        NOT NULL DEFAULT 0,
                        ex               VARCHAR(1024)  NOT NULL DEFAULT '',
                        created_at       BIGINT          NOT NULL DEFAULT 0,
                        updated_at       BIGINT          NOT NULL DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_group_members" -> """
                    CREATE TABLE im_group_members (
                        id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        group_id        VARCHAR(64)     NOT NULL,
                        user_id         VARCHAR(64)     NOT NULL,
                        nickname        VARCHAR(255)    NOT NULL DEFAULT '',
                        face_url        VARCHAR(512)    NOT NULL DEFAULT '',
                        role_level      INT             NOT NULL DEFAULT 0,
                        join_source     INT             NOT NULL DEFAULT 0,
                        inviter_user_id VARCHAR(64)     NOT NULL DEFAULT '',
                        operator_user_id VARCHAR(64)    NOT NULL DEFAULT '',
                        mute_end_time   BIGINT          NOT NULL DEFAULT 0,
                        ex              VARCHAR(1024)   NOT NULL DEFAULT '',
                        joined_at       BIGINT          NOT NULL DEFAULT 0,
                        UNIQUE KEY uk_member (group_id, user_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_group_requests" -> """
                    CREATE TABLE im_group_requests (
                        id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        user_id         VARCHAR(64)     NOT NULL,
                        group_id        VARCHAR(64)     NOT NULL,
                        handle_result   INT             NOT NULL DEFAULT 0,
                        req_msg         VARCHAR(512)    NOT NULL DEFAULT '',
                        handled_msg     VARCHAR(512)    NOT NULL DEFAULT '',
                        handler_user_id VARCHAR(64)     NOT NULL DEFAULT '',
                        handled_time    BIGINT          NOT NULL DEFAULT 0,
                        join_source     INT             NOT NULL DEFAULT 0,
                        inviter_user_id VARCHAR(64)     NOT NULL DEFAULT '',
                        ex              VARCHAR(1024)   NOT NULL DEFAULT '',
                        created_at      BIGINT          NOT NULL DEFAULT 0,
                        KEY idx_group_req (group_id, handle_result)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_conversations" -> """
                    CREATE TABLE im_conversations (
                        id                  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        owner_user_id       VARCHAR(64) NOT NULL,
                        conversation_id     VARCHAR(128) NOT NULL,
                        conversation_type   INT         NOT NULL DEFAULT 0,
                        user_id             VARCHAR(64) NOT NULL DEFAULT '',
                        group_id            VARCHAR(64) NOT NULL DEFAULT '',
                        recv_msg_opt        INT         NOT NULL DEFAULT 0,
                        is_pinned           INT         NOT NULL DEFAULT 0,
                        is_private_chat     INT         NOT NULL DEFAULT 0,
                        burn_duration       INT         NOT NULL DEFAULT 0,
                        group_at_type       INT         NOT NULL DEFAULT 0,
                        attached_info       VARCHAR(512) NOT NULL DEFAULT '',
                        ex                  VARCHAR(1024) NOT NULL DEFAULT '',
                        max_seq             BIGINT      NOT NULL DEFAULT 0,
                        min_seq             BIGINT      NOT NULL DEFAULT 0,
                        unread_count        INT         NOT NULL DEFAULT 0,
                        is_msg_destruct     INT         NOT NULL DEFAULT 0,
                        msg_destruct_time   INT         NOT NULL DEFAULT 0,
                        created_at          BIGINT      NOT NULL DEFAULT 0,
                        updated_at          BIGINT      NOT NULL DEFAULT 0,
                        UNIQUE KEY uk_conv (owner_user_id, conversation_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_messages" -> """
                    CREATE TABLE im_messages (
                        id                  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
                        client_msg_id       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '客户端消息ID（客户端生成，用于去重）',
                        server_msg_id       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '服务端消息ID（唯一标识）',
                        conversation_id     VARCHAR(128) NOT NULL COMMENT '会话ID',
                        seq                 BIGINT      NOT NULL DEFAULT 0 COMMENT '会话内全局递增序号',
                        send_id             VARCHAR(64) NOT NULL DEFAULT '' COMMENT '发送者ID',
                        recv_id             VARCHAR(64) NOT NULL DEFAULT '' COMMENT '接收者ID（单聊时）',
                        group_id            VARCHAR(64) NOT NULL DEFAULT '' COMMENT '群组ID（群聊时）',
                        sender_platform_id  INT         NOT NULL DEFAULT 0 COMMENT '发送端平台: 1=iOS, 2=Android, 3=Win, 4=Mac, 5=Web',
                        sender_nickname     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '发送者昵称（冗余，不可变）',
                        sender_face_url     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '发送者头像（冗余，不可变）',
                        session_type        INT         NOT NULL DEFAULT 0 COMMENT '会话类型: 1=单聊, 2=群聊',
                        msg_from            INT         NOT NULL DEFAULT 0 COMMENT '消息来源: 0=用户, 1=系统',
                        content_type        INT         NOT NULL DEFAULT 0 COMMENT '消息内容类型（101=文本, 102=图片, 103=文件, ...）',
                        content             MEDIUMTEXT,
                        status              INT         NOT NULL DEFAULT 0 COMMENT '消息状态: 0=正常, 1=已撤回',
                        revoke_user_id      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '撤回者ID',
                        revoke_role         INT         NOT NULL DEFAULT 0 COMMENT '撤回者角色',
                        revoke_nickname     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '撤回者昵称',
                        revoke_time         BIGINT      NOT NULL DEFAULT 0 COMMENT '撤回时间(毫秒)',
                        at_user_ids         TEXT COMMENT '@用户ID列表(逗号分隔)',
                        offline_title       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '离线推送标题',
                        offline_desc        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '离线推送描述',
                        offline_ex          VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '离线推送扩展',
                        ios_push_sound      VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'iOS推送音效',
                        ios_badge_count     INT         NOT NULL DEFAULT 0 COMMENT '是否更新iOS角标',
                        attached_info       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '附加信息',
                        ex                  VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '扩展字段',
                        sent_at             BIGINT      NOT NULL DEFAULT 0 COMMENT '发送时间(毫秒)',
                        created_at          BIGINT      NOT NULL DEFAULT 0 COMMENT '创建时间(毫秒)',
                        KEY idx_conv_seq (conversation_id, seq),
                        KEY idx_send (send_id),
                        KEY idx_recv (recv_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表'
                    """;
            case "im_message_read_states" -> """
                    CREATE TABLE im_message_read_states (
                        id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
                        user_id           VARCHAR(64) NOT NULL COMMENT '用户ID',
                        conversation_id   VARCHAR(128) NOT NULL COMMENT '会话ID',
                        read_seq          BIGINT      NOT NULL DEFAULT 0 COMMENT '该用户在此会话已读到的最大消息序号',
                        delivered_seq     BIGINT      NOT NULL DEFAULT 0 COMMENT '该用户在此会话已投递到的最大消息序号',
                        unread_count      INT         NOT NULL DEFAULT 0 COMMENT '该用户在此会话的未读消息数缓存',
                        updated_at        BIGINT      NOT NULL DEFAULT 0 COMMENT '更新时间(毫秒)',
                        UNIQUE KEY uk_user_conversation_read (user_id, conversation_id),
                        KEY idx_conversation_read (conversation_id),
                        KEY idx_updated_read (updated_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息已读状态表'
                    """;
            case "im_message_visibility" -> """
                    CREATE TABLE im_message_visibility (
                        id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
                        user_id           VARCHAR(64) NOT NULL COMMENT '用户ID',
                        conversation_id   VARCHAR(128) NOT NULL COMMENT '会话ID',
                        seq               BIGINT      NOT NULL COMMENT '会话内消息序号',
                        client_msg_id     VARCHAR(64) NOT NULL DEFAULT '' COMMENT '客户端消息ID，便于按消息ID定位可见性记录',
                        visibility_state  INT         NOT NULL DEFAULT 0 COMMENT '可见性状态: 0=可见, 1=用户删除, 2=会话清空隐藏, 3=合规隐藏',
                        operator_user_id  VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作人用户ID',
                        reason            VARCHAR(255) NOT NULL DEFAULT '' COMMENT '状态变更原因',
                        updated_at        BIGINT      NOT NULL DEFAULT 0 COMMENT '更新时间(毫秒)',
                        UNIQUE KEY uk_user_message_visibility (user_id, conversation_id, seq),
                        KEY idx_conversation_visibility (conversation_id, seq),
                        KEY idx_client_msg_visibility (client_msg_id),
                        KEY idx_state_visibility (visibility_state, updated_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息用户可见性表'
                    """;
            case "im_objects" -> """
                    CREATE TABLE im_objects (
                        id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        name            VARCHAR(255)    NOT NULL DEFAULT '',
                        user_id         VARCHAR(64)     NOT NULL DEFAULT '',
                        hash            VARCHAR(128)    NOT NULL DEFAULT '',
                        engine          VARCHAR(32)     NOT NULL DEFAULT '',
                        object_key      VARCHAR(512)    NOT NULL DEFAULT '',
                        file_size       BIGINT          NOT NULL DEFAULT 0,
                        content_type    VARCHAR(128)    NOT NULL DEFAULT '',
                        file_group      VARCHAR(64)     NOT NULL DEFAULT '',
                        ex              VARCHAR(1024)   NOT NULL DEFAULT '',
                        created_at      BIGINT          NOT NULL DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_sequences" -> """
                    CREATE TABLE im_sequences (
                        conversation_id VARCHAR(128) NOT NULL PRIMARY KEY,
                        max_seq         BIGINT       NOT NULL DEFAULT 0,
                        min_seq         BIGINT       NOT NULL DEFAULT 0,
                        updated_at      BIGINT       NOT NULL DEFAULT 0
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_seq_users" -> """
                    CREATE TABLE im_seq_users (
                        id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        user_id         VARCHAR(64)     NOT NULL,
                        conversation_id VARCHAR(128)    NOT NULL,
                        min_seq         BIGINT          NOT NULL DEFAULT 0,
                        max_seq         BIGINT          NOT NULL DEFAULT 0,
                        read_seq        BIGINT          NOT NULL DEFAULT 0,
                        updated_at      BIGINT          NOT NULL DEFAULT 0,
                        UNIQUE KEY uk_seq_user (user_id, conversation_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_sync_versions" -> """
                    CREATE TABLE im_sync_versions (
                        user_id         VARCHAR(64)     NOT NULL,
                        entity_type     VARCHAR(32)     NOT NULL,
                        version         BIGINT          NOT NULL DEFAULT 0,
                        PRIMARY KEY (user_id, entity_type)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            case "im_sync_changes" -> """
                    CREATE TABLE im_sync_changes (
                        id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        user_id         VARCHAR(64)     NOT NULL,
                        entity_type     VARCHAR(32)     NOT NULL,
                        entity_id       VARCHAR(128)   NOT NULL,
                        version         BIGINT          NOT NULL DEFAULT 0,
                        action          VARCHAR(8)      NOT NULL DEFAULT 'insert',
                        created_at      BIGINT          NOT NULL DEFAULT 0,
                        INDEX idx_sync_lookup (user_id, entity_type, version)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            default -> {
                log.warn("Unknown table: {}", table);
                yield null;
            }
        };
    }
}

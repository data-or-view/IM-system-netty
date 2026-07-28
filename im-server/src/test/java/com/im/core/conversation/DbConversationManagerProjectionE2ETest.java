package com.im.core.conversation;

import com.im.api.Conversation;
import com.im.api.Message;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.core.db.DatabaseConfiguration;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.SchemaInitializer;
import com.im.core.sync.DbIncrementalSync;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real-MySQL coverage for monotonic, idempotent conversation projection. */
class DbConversationManagerProjectionE2ETest {

    private static final String DATABASE_NAME =
            "im_conversation_projection_e2e_" + Long.toUnsignedString(System.nanoTime(), 36);
    private static final String OWNER_USER_ID = "projection-receiver";
    private static final String OTHER_USER_ID = "projection-sender";
    private static final String CONVERSATION_ID = "single_projection-receiver_projection-sender";
    private static final RetryExecutor DIRECT_RETRY = new DirectRetryExecutor();

    private static MysqlDataSource adminDataSource;
    private static CommitObservingSync sync;
    private static DbConversationManager manager;

    @BeforeAll
    static void initializeDatabase() throws Exception {
        String configuredUrl = env(
                "IM_E2E_MYSQL_JDBC_URL",
                "IM_IT_MYSQL_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/im_system?useUnicode=true&characterEncoding=utf-8"
                        + "&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        String username = env("IM_E2E_MYSQL_USER", "IM_IT_MYSQL_USER", "root");
        String password = env("IM_E2E_MYSQL_PASSWORD", "IM_IT_MYSQL_PASSWORD", "123456");
        String databaseUrl = withDatabase(configuredUrl, DATABASE_NAME);

        adminDataSource = dataSource(withDatabase(configuredUrl, ""), username, password);
        try (Connection connection = adminDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + DATABASE_NAME
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            assumeTrue(false, "Real MySQL conversation projection prerequisite unavailable: " + e.getMessage());
        }

        MyBatisPlusFactory.shutdown();
        MyBatisPlusFactory.init(new DatabaseConfiguration.Builder()
                .jdbcUrl(databaseUrl)
                .username(username)
                .password(password)
                .maximumPoolSize(4)
                .connectionTimeoutMs(2_000)
                .build());
        SchemaInitializer.initialize(MyBatisPlusFactory.getDataSource(), "auto");
        sync = new CommitObservingSync(DIRECT_RETRY);
        manager = new DbConversationManager(DIRECT_RETRY, sync);
    }

    @BeforeEach
    void clearProjectionState() throws Exception {
        try (Connection connection = MyBatisPlusFactory.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM im_sync_changes");
            statement.executeUpdate("DELETE FROM im_sync_versions");
            statement.executeUpdate("DELETE FROM im_message_read_states");
            statement.executeUpdate("DELETE FROM im_conversation_projection_events");
            statement.executeUpdate("DELETE FROM im_seq_users");
            statement.executeUpdate("DELETE FROM im_conversations");
        }
        sync.clear();
    }

    @AfterAll
    static void removeDatabase() {
        MyBatisPlusFactory.shutdown();
        if (adminDataSource == null) {
            return;
        }
        try (Connection connection = adminDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + DATABASE_NAME + "`");
        } catch (SQLException ignored) {
            // A test failure already reports the behavior under test; cleanup is best effort.
        }
    }

    @Test
    void outOfOrderReplayCannotRegressConversationPreviewOrMaxSequence() {
        project(inbound("m2", 2, 11, "second"));
        project(inbound("m1", 1, 99, "first"));

        Conversation conversation = manager.getConversation(OWNER_USER_ID, CONVERSATION_ID);

        assertNotNull(conversation);
        assertEquals(2, conversation.getLastMsgSeq());
        assertEquals("m2", conversation.getLastMsgId());
        assertEquals("second", conversation.getLastMsgContent());
    }

    @Test
    void duplicateInboundProjectionCountsOnceAndOwnMessageIsNotUnread() throws Exception {
        project(inbound("m1", 1, 71, "hello"));
        project(inbound("m1", 1, 72, "hello retry"));
        project(outbound("m2", 2, 73, "reply"));

        assertEquals(1, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(1, queryLong(
                "SELECT COUNT(*) FROM im_conversation_projection_events "
                        + "WHERE owner_user_id=? AND conversation_id=?",
                OWNER_USER_ID, CONVERSATION_ID));
    }

    @Test
    void unreadCountUsesDistinctInboundEventsInsteadOfSequenceDistance() {
        project(inbound("m10", 10, 1, "ten"));
        project(inbound("m20", 20, 2, "twenty"));

        assertEquals(2, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));

        manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 10);

        assertEquals(1, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));
    }

    @Test
    void readSequenceIsMonotonicAndCappedAtObservedMaximum() throws Exception {
        project(inbound("m1", 1, 21, "one"));
        project(inbound("m2", 2, 22, "two"));

        manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 1);
        assertEquals(1, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(1, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));

        manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 99);
        manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 1);

        assertEquals(2, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(0, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(2, queryLong(
                "SELECT read_seq FROM im_seq_users WHERE user_id=? AND conversation_id=?",
                OWNER_USER_ID, CONVERSATION_ID));
    }

    @Test
    void readIntentAheadOfProjectionIsAppliedWhenTheMessageIsProjected() {
        project(inbound("m1", 1, 41, "first"));

        manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 2);
        assertEquals(1, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));

        project(inbound("m2", 2, 42, "arrived after read"));

        assertEquals(2, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(0, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));
    }

    @Test
    void syncChangeIsRecordedOnlyAfterProjectionCommit() throws Exception {
        project(inbound("m1", 1, 31, "committed"));

        assertEquals(List.of(new ProjectionObservation(1, 1)), sync.observations());
        assertEquals(1, queryLong(
                "SELECT COUNT(*) FROM im_sync_changes "
                        + "WHERE user_id=? AND entity_type='conversation' AND entity_id=?",
                OWNER_USER_ID, CONVERSATION_ID));
    }

    private static void project(Message message) {
        manager.updateOnMessage(
                OWNER_USER_ID,
                CONVERSATION_ID,
                message,
                OWNER_USER_ID.equals(message.getFromUserId()));
    }

    private static Message inbound(String messageId, long messageSeq, long sequenceId, String content) {
        return message(messageId, OTHER_USER_ID, OWNER_USER_ID, messageSeq, sequenceId, content);
    }

    private static Message outbound(String messageId, long messageSeq, long sequenceId, String content) {
        return message(messageId, OWNER_USER_ID, OTHER_USER_ID, messageSeq, sequenceId, content);
    }

    private static Message message(String messageId, String fromUserId, String toUserId,
                                   long messageSeq, long sequenceId, String content) {
        Message message = Message.createSingle(fromUserId, toUserId, CONVERSATION_ID, 101, content, messageSeq);
        message.setMessageId(messageId);
        message.setSequenceId(sequenceId);
        return message;
    }

    private static long queryLong(String sql, String... parameters) throws SQLException {
        try (Connection connection = MyBatisPlusFactory.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return 0;
                }
                return result.getLong(1);
            }
        }
    }

    private static MysqlDataSource dataSource(String url, String username, String password) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(url);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static String env(String primary, String secondary, String defaultValue) {
        if (System.getenv().containsKey(primary)) {
            return System.getenv(primary);
        }
        if (System.getenv().containsKey(secondary)) {
            return System.getenv(secondary);
        }
        return defaultValue;
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

    private record ProjectionObservation(long maxSequence, long inboundEventCount) {
    }

    private static final class CommitObservingSync extends DbIncrementalSync {
        private final List<ProjectionObservation> observations = new ArrayList<>();

        private CommitObservingSync(RetryExecutor retryExecutor) {
            super(retryExecutor);
        }

        @Override
        public void recordChange(String userId, String entityType, String entityId, String action) {
            try {
                long maxSequence = queryLong(
                        "SELECT max_seq FROM im_conversations "
                                + "WHERE owner_user_id=? AND conversation_id=?",
                        userId, entityId);
                long eventCount = queryLong(
                        "SELECT COUNT(*) FROM im_conversation_projection_events "
                                + "WHERE owner_user_id=? AND conversation_id=?",
                        userId, entityId);
                observations.add(new ProjectionObservation(maxSequence, eventCount));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            super.recordChange(userId, entityType, entityId, action);
        }

        private List<ProjectionObservation> observations() {
            return List.copyOf(observations);
        }

        private void clear() {
            observations.clear();
        }
    }

    private static final class DirectRetryExecutor implements RetryExecutor {
        @Override
        public <T> T execute(RetryConfig config, Callable<T> callable) {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}

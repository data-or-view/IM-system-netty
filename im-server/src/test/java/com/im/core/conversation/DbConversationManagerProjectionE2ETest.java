package com.im.core.conversation;

import com.im.api.Conversation;
import com.im.api.Message;
import com.im.bootstrap.BaseE2ETest;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.sync.DbIncrementalSync;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL coverage for monotonic, idempotent conversation projection. */
class DbConversationManagerProjectionE2ETest extends BaseE2ETest {

    private static final String OWNER_USER_ID = "projection-receiver";
    private static final String OTHER_USER_ID = "projection-sender";
    private static final String CONVERSATION_ID = "single_projection-receiver_projection-sender";
    private static final RetryExecutor DIRECT_RETRY = new DirectRetryExecutor();

    private static IsolatedMySqlDatabase mysql;
    private static CommitObservingSync sync;
    private static DbConversationManager manager;

    @BeforeAll
    static void initializeDatabase() throws Exception {
        mysql = openIsolatedMySqlDatabase("im_conversation_projection_e2e", true);
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
        if (mysql != null) {
            mysql.close();
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
    void arbitraryFutureReadDoesNotAcknowledgeMessagesAuthorizedLater() {
        project(inbound("m1", 1, 51, "one"));
        project(inbound("m2", 2, 52, "two"));

        manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 99);
        project(inbound("m3", 3, 53, "three"));

        assertEquals(2, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(1, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));
    }

    @Test
    void staleUnboundedPendingReadCannotConsumeNewlyProjectedMessage() throws Exception {
        project(inbound("m1", 1, 54, "one"));
        project(inbound("m2", 2, 55, "two"));
        manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 2);
        setPendingReadSequence(99);

        project(inbound("m3", 3, 56, "three"));

        assertEquals(2, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(0, queryLong(
                "SELECT pending_read_seq FROM im_message_read_states "
                        + "WHERE user_id=? AND conversation_id=?",
                OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(1, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));
    }

    @Test
    void readIntentAheadOfProjectionIsAppliedWhenTheAuthorizedMessageIsProjected() throws Exception {
        project(inbound("m1", 1, 41, "first"));
        authorizeDeliveredSequence(2);

        manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 2);
        assertEquals(1, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));

        project(inbound("m2", 2, 42, "arrived after read"));

        assertEquals(2, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(0, manager.getUnreadCount(OWNER_USER_ID, CONVERSATION_ID));
    }

    @Test
    void concurrentProjectionCannotLeaveAuthorizedReadIntentPending() throws Exception {
        project(inbound("m1", 1, 61, "first"));
        authorizeDeliveredSequence(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = MyBatisPlusFactory.getDataSource().getConnection();
             PreparedStatement lockConversation = blocker.prepareStatement(
                     "SELECT max_seq FROM im_conversations "
                             + "WHERE owner_user_id=? AND conversation_id=? FOR UPDATE")) {
            blocker.setAutoCommit(false);
            lockConversation.setString(1, OWNER_USER_ID);
            lockConversation.setString(2, CONVERSATION_ID);
            try (ResultSet result = lockConversation.executeQuery()) {
                assertTrue(result.next());
            }

            Future<?> projection = executor.submit(() ->
                    project(inbound("m2", 2, 62, "second")));
            assertThrows(TimeoutException.class, () -> projection.get(200, TimeUnit.MILLISECONDS));
            Future<?> markRead = executor.submit(() ->
                    manager.markRead(OWNER_USER_ID, CONVERSATION_ID, 2));
            assertThrows(TimeoutException.class, () -> markRead.get(200, TimeUnit.MILLISECONDS));

            blocker.commit();
            projection.get(5, TimeUnit.SECONDS);
            markRead.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, manager.getReadSeq(OWNER_USER_ID, CONVERSATION_ID));
        assertEquals(0, queryLong(
                "SELECT pending_read_seq FROM im_message_read_states "
                        + "WHERE user_id=? AND conversation_id=?",
                OWNER_USER_ID, CONVERSATION_ID));
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

    private static void authorizeDeliveredSequence(long deliveredSeq) throws SQLException {
        try (Connection connection = MyBatisPlusFactory.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE im_message_read_states SET delivered_seq=? "
                             + "WHERE user_id=? AND conversation_id=?")) {
            statement.setLong(1, deliveredSeq);
            statement.setString(2, OWNER_USER_ID);
            statement.setString(3, CONVERSATION_ID);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void setPendingReadSequence(long pendingReadSeq) throws SQLException {
        try (Connection connection = MyBatisPlusFactory.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE im_message_read_states SET pending_read_seq=? "
                             + "WHERE user_id=? AND conversation_id=?")) {
            statement.setLong(1, pendingReadSeq);
            statement.setString(2, OWNER_USER_ID);
            statement.setString(3, CONVERSATION_ID);
            assertEquals(1, statement.executeUpdate());
        }
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

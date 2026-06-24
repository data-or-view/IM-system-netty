package com.im.core.reliability;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.MessageSendFailureRecord;
import com.im.core.db.DatabaseConfiguration;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.SchemaInitializer;
import com.im.core.db.entity.MessageSendFailureEntity;
import com.im.core.db.mapper.MessageSendFailureMapper;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import com.im.infrastructure.message.MessageBusException;
import com.im.infrastructure.message.rocketmq.RocketMqMessageQueue;
import com.im.infrastructure.message.rocketmq.RocketMqMessageQueueProperties;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbSendMessageFailureStoreIT {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();
    private static final String TEST_DB = "im_system_rocketmq_it";
    private static final String MYSQL_URL = System.getenv().getOrDefault("IM_IT_MYSQL_JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/" + TEST_DB
                    + "?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
    private static final String MYSQL_USER = System.getenv().getOrDefault("IM_IT_MYSQL_USER", "root");
    private static final String MYSQL_PASSWORD = System.getenv().getOrDefault("IM_IT_MYSQL_PASSWORD", "123456");
    private static final String TEST_PREFIX = "it-" + Long.toString(System.currentTimeMillis(), 36) + "-";

    private final DbSendMessageFailureStore store = new DbSendMessageFailureStore();

    @BeforeAll
    static void initDatabase() throws Exception {
        assumeMysqlReachable();
        createDefaultTestDatabaseIfNeeded();
        MyBatisPlusFactory.shutdown();
        MyBatisPlusFactory.init(new DatabaseConfiguration.Builder()
                .jdbcUrl(MYSQL_URL)
                .username(MYSQL_USER)
                .password(MYSQL_PASSWORD)
                .maximumPoolSize(4)
                .connectionTimeoutMs(2_000)
                .build());
        SchemaInitializer.initialize(MyBatisPlusFactory.getDataSource(), "auto");
    }

    @AfterEach
    void cleanupRecords() {
        cleanupTestRecords();
    }

    @AfterAll
    static void shutdownDatabase() {
        cleanupTestRecords();
        MyBatisPlusFactory.shutdown();
    }

    @Test
    void recordsFailureIntoRealMysqlTable() throws Exception {
        Message message = message(nextId("record"), "single_it_record");

        store.recordFailure("deliver", message, new IllegalStateException("db write failed"));

        MessageSendFailureEntity entity = selectByMessageId(message.getMessageId());
        assertNotNull(entity);
        assertEquals("deliver", entity.getTopic());
        assertEquals(message.getMessageId(), entity.getMessageId());
        assertEquals("single_it_record", entity.getConversationId());
        assertEquals(DbSendMessageFailureStore.STATUS_PENDING, entity.getStatus());
        assertEquals(0, entity.getAttemptCount());
        assertTrue(entity.getNextRetryAt() > 0);
        assertTrue(entity.getLastError().contains("db write failed"));
        Map<String, Object> payload = MAPPER.readValue(entity.getPayloadJson(), new TypeReference<>() {});
        assertEquals(message.getMessageId(), payload.get("_mid"));
        assertEquals("single_it_record", payload.get("conversationId"));
    }

    @Test
    void claimsRealMysqlRecordWithLeaseAndDoesNotDoubleClaimBeforeLeaseExpires() {
        Message message = message(nextId("claim"), "single_it_claim");
        store.recordFailure("deliver", message, new IllegalStateException("claim me"));
        forceDue(message.getMessageId(), 1L);

        long now = System.currentTimeMillis();
        long leaseMillis = 20_000L;
        List<MessageSendFailureRecord> claimed = store.claimDueFailures(now, 10, leaseMillis);

        assertEquals(List.of(message.getMessageId()), claimed.stream().map(MessageSendFailureRecord::messageId).toList());
        MessageSendFailureEntity retrying = selectByMessageId(message.getMessageId());
        assertEquals(DbSendMessageFailureStore.STATUS_RETRYING, retrying.getStatus());
        assertEquals(now + leaseMillis, retrying.getNextRetryAt());

        List<MessageSendFailureRecord> secondClaim = store.claimDueFailures(now + 1, 10, leaseMillis);
        assertFalse(secondClaim.stream().anyMatch(record -> Objects.equals(record.messageId(), message.getMessageId())));

        forceStatusAndNextRetryAt(message.getMessageId(), DbSendMessageFailureStore.STATUS_RETRYING, now - 1);
        List<MessageSendFailureRecord> reclaimed = store.claimDueFailures(now, 10, leaseMillis);
        assertEquals(List.of(message.getMessageId()), reclaimed.stream().map(MessageSendFailureRecord::messageId).toList());
    }

    @Test
    void failedCompensationUpdatesRealMysqlRetryStateWithoutMarkingRepublished() {
        Message message = message(nextId("retry"), "single_it_retry");
        store.recordFailure("deliver", message, new IllegalStateException("first failure"));
        forceDue(message.getMessageId(), 1L);
        MessageFailureCompensator compensator = new MessageFailureCompensator(
                new AlwaysFailingQueue(), store, 10, 3, 1000, 1000, 5000);

        int replayed = compensator.replayDueFailures();

        assertEquals(1, replayed);
        MessageSendFailureEntity entity = selectByMessageId(message.getMessageId());
        assertEquals(DbSendMessageFailureStore.STATUS_PENDING, entity.getStatus());
        assertEquals(1, entity.getAttemptCount());
        assertTrue(entity.getNextRetryAt() > System.currentTimeMillis());
        assertTrue(entity.getLastError().contains("publish unavailable"));
    }

    @Test
    void compensatorClaimsMysqlRecordRepublishesToRocketMqAndMarksRepublished() throws Exception {
        assumeRocketMqReachable();
        Message message = message(nextId("rocketmq"), "single_it_rocketmq");
        store.recordFailure("deliver", message, new IllegalStateException("needs republish"));
        forceDue(message.getMessageId(), 1L);
        RocketMqMessageQueueProperties properties = isolatedRocketMqProperties("mysql-dlq");
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties, "node-mysql-dlq-it");
        CountDownLatch received = new CountDownLatch(1);
        List<Message> republished = new CopyOnWriteArrayList<>();

        queue.subscribe("deliver", msg -> {
            if (message.getMessageId().equals(msg.getMessageId())) {
                republished.add(msg);
                received.countDown();
            }
        });

        try {
            queue.start();
            MessageFailureCompensator compensator = new MessageFailureCompensator(
                    queue, store, 10, 3, 1000, 1000, 5000);

            int replayed = compensator.replayDueFailures();

            assertEquals(1, replayed);
            assertTrue(received.await(40, TimeUnit.SECONDS), "republished MySQL business-DLQ message was not consumed");
            assertEquals(message.getMessageId(), republished.getFirst().getMessageId());
            MessageSendFailureEntity entity = selectByMessageId(message.getMessageId());
            assertEquals(DbSendMessageFailureStore.STATUS_REPUBLISHED, entity.getStatus());
            assertEquals(0, entity.getAttemptCount());
        } finally {
            queue.stop();
        }
    }

    private static Message message(String messageId, String conversationId) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setMessageSeq(ThreadLocalRandom.current().nextLong(1, 100_000));
        message.setFromUserId("alice-it");
        message.setToUserId("bob-it");
        message.setContentType(101);
        message.setContent("{\"text\":\"mysql dlq it\"}");
        return message;
    }

    private static String nextId(String scenario) {
        return TEST_PREFIX + scenario + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static MessageSendFailureEntity selectByMessageId(String messageId) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            return session.getMapper(MessageSendFailureMapper.class)
                    .selectOne(new QueryWrapper<MessageSendFailureEntity>()
                            .eq("message_id", messageId)
                            .last("LIMIT 1"));
        }
    }

    private static void forceDue(String messageId, long nextRetryAt) {
        forceStatusAndNextRetryAt(messageId, DbSendMessageFailureStore.STATUS_PENDING, nextRetryAt);
    }

    private static void forceStatusAndNextRetryAt(String messageId, String status, long nextRetryAt) {
        try (SqlSession session = MyBatisPlusFactory.openSession()) {
            MessageSendFailureEntity update = new MessageSendFailureEntity();
            update.setStatus(status);
            update.setNextRetryAt(nextRetryAt);
            update.setUpdatedAt(System.currentTimeMillis());
            session.getMapper(MessageSendFailureMapper.class).update(update,
                    new UpdateWrapper<MessageSendFailureEntity>().eq("message_id", messageId));
            session.commit();
        }
    }

    private static void cleanupTestRecords() {
        try {
            if (MyBatisPlusFactory.getDataSource() == null) {
                return;
            }
            try (SqlSession session = MyBatisPlusFactory.openSession()) {
                session.getMapper(MessageSendFailureMapper.class).delete(
                        new QueryWrapper<MessageSendFailureEntity>().likeRight("message_id", TEST_PREFIX));
                session.commit();
            }
        } catch (Exception ignored) {
            // Best effort cleanup. Test assertions already cover the behavior under test.
        }
    }

    private static void assumeMysqlReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(mysqlHost(), mysqlPort()), 1500);
        } catch (IOException e) {
            Assumptions.abort("MySQL integration tests skipped because MySQL is unreachable: "
                    + mysqlHost() + ":" + mysqlPort() + " (" + e.getMessage() + ")");
        }
    }

    private static String mysqlHost() {
        String authority = MYSQL_URL.substring("jdbc:mysql://".length());
        String hostPort = authority.substring(0, authority.indexOf('/'));
        return hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
    }

    private static int mysqlPort() {
        String authority = MYSQL_URL.substring("jdbc:mysql://".length());
        String hostPort = authority.substring(0, authority.indexOf('/'));
        return hostPort.contains(":") ? Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1)) : 3306;
    }

    private static void createDefaultTestDatabaseIfNeeded() throws Exception {
        if (System.getenv().containsKey("IM_IT_MYSQL_JDBC_URL")) {
            return;
        }
        String serverUrl = "jdbc:mysql://" + mysqlHost() + ":" + mysqlPort()
                + "/?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(serverUrl, MYSQL_USER, MYSQL_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + TEST_DB + " DEFAULT CHARACTER SET utf8mb4");
        }
    }

    private static void assumeRocketMqReachable() {
        String[] hostPort = rocketMqNameServer().split(":", 2);
        Assumptions.assumeTrue(hostPort.length == 2, "RocketMQ name-server must use host:port");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1])), 1500);
        } catch (IOException | NumberFormatException e) {
            Assumptions.abort("RocketMQ integration tests skipped because name-server is unreachable: "
                    + rocketMqNameServer() + " (" + e.getMessage() + ")");
        }
    }

    private static String rocketMqNameServer() {
        return System.getenv().getOrDefault("IM_ROCKETMQ_IT_NAME_SERVER", "127.0.0.1:9876");
    }

    private static RocketMqMessageQueueProperties isolatedRocketMqProperties(String testName) {
        String suffix = Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toString(ThreadLocalRandom.current().nextInt(1_000_000), 36);
        return new RocketMqMessageQueueProperties(
                rocketMqNameServer(),
                "im-it-producer-" + testName + "-" + suffix,
                "im-it-consumer-" + testName + "-" + suffix,
                "im-it-" + testName + "-" + suffix + "-",
                Duration.ofSeconds(3),
                1);
    }

    private static final class AlwaysFailingQueue implements IMessageQueue {
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void publish(String topic, Message msg) {
            throw new MessageBusException("publish unavailable");
        }
        @Override public void subscribe(String topic, MessageHandler handler) {}
        @Override public void unsubscribe(String topic, MessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }
}

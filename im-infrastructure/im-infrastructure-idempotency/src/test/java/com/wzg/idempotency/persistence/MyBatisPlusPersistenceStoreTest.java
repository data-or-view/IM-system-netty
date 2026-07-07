package com.wzg.idempotency.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.wzg.idempotency.exception.IdempotencyItemAlreadyExistsException;
import com.wzg.idempotency.exception.IdempotencyItemNotFoundException;
import com.wzg.idempotency.persistence.mybatis.IdempotencyRecordMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MyBatisPlusPersistenceStoreTest {

    private MyBatisPlusPersistenceStore store;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:idempotency_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE im_idempotency_records (
                      idempotency_key VARCHAR(255) PRIMARY KEY,
                      status VARCHAR(32) NOT NULL,
                      expiry_timestamp BIGINT NOT NULL,
                      in_progress_expiry_timestamp BIGINT NOT NULL DEFAULT 0,
                      response_data CLOB,
                      payload_hash VARCHAR(255) NOT NULL DEFAULT '',
                      created_at BIGINT NOT NULL,
                      updated_at BIGINT NOT NULL
                    )
                    """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(IdempotencyRecordMapper.class);
        SqlSessionFactory sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        store = new MyBatisPlusPersistenceStore(sqlSessionFactory);
    }

    @Test
    void getRecordThrowsItemNotFoundWhenKeyDoesNotExist() {
        assertThrows(IdempotencyItemNotFoundException.class, () -> store.getRecord("missing-key"));
    }

    @Test
    void putRecordThrowsItemAlreadyExistsWithExistingRecordWhenNonExpiredRecordExists() throws Exception {
        Instant now = Instant.now();
        DataRecord existing = inProgress("same-key", now.plusSeconds(60).getEpochSecond());
        store.putRecord(existing, now);

        IdempotencyItemAlreadyExistsException error = assertThrows(
                IdempotencyItemAlreadyExistsException.class,
                () -> store.putRecord(inProgress("same-key", now.plusSeconds(120).getEpochSecond()), now));

        assertEquals("same-key", error.getDataRecord().orElseThrow().getIdempotencyKey());
    }

    @Test
    void putRecordOverwritesExpiredRecord() throws Exception {
        Instant now = Instant.now();
        store.putRecord(inProgress("same-key", now.minusSeconds(1).getEpochSecond()), now.minusSeconds(60));

        DataRecord replacement = inProgress("same-key", now.plusSeconds(120).getEpochSecond());
        assertDoesNotThrow(() -> store.putRecord(replacement, now));

        DataRecord stored = store.getRecord("same-key");
        assertEquals(replacement.getExpiryTimestamp(), stored.getExpiryTimestamp());
    }

    @Test
    void updateRecordStoresCompletedResponseWhenRecordExists() throws Exception {
        Instant now = Instant.now();
        store.putRecord(inProgress("complete-key", now.plusSeconds(60).getEpochSecond()), now);

        store.updateRecord(new DataRecord(
                "complete-key",
                DataRecordStatus.COMPLETED,
                now.plusSeconds(300).getEpochSecond(),
                "{\"ok\":true}",
                "payload",
                OptionalLong.empty()));

        DataRecord stored = store.getRecord("complete-key");
        assertEquals(DataRecordStatus.COMPLETED, stored.getStatus());
        assertEquals("{\"ok\":true}", stored.getResponseData());
        assertEquals("payload", stored.getPayloadHash());
    }

    @Test
    void updateRecordIgnoresMissingRecordLikeJdbcStore() {
        assertDoesNotThrow(() -> store.updateRecord(new DataRecord(
                "missing-key",
                DataRecordStatus.COMPLETED,
                Instant.now().plusSeconds(60).getEpochSecond(),
                "ok",
                "")));
    }

    @Test
    void deleteRecordRemovesExistingRecordAndIgnoresMissingRecord() throws Exception {
        Instant now = Instant.now();
        store.putRecord(inProgress("delete-key", now.plusSeconds(60).getEpochSecond()), now);

        store.deleteRecord("delete-key");
        assertThrows(IdempotencyItemNotFoundException.class, () -> store.getRecord("delete-key"));
        assertDoesNotThrow(() -> store.deleteRecord("delete-key"));
    }

    private static DataRecord inProgress(String key, long expiryTimestamp) {
        return new DataRecord(
                key,
                DataRecordStatus.INPROGRESS,
                expiryTimestamp,
                null,
                "",
                OptionalLong.of(Instant.now().plusSeconds(30).toEpochMilli()));
    }
}

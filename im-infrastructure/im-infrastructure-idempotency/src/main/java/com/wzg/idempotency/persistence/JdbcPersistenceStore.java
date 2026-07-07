package com.wzg.idempotency.persistence;

import com.wzg.idempotency.exception.IdempotencyItemAlreadyExistsException;
import com.wzg.idempotency.exception.IdempotencyItemNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Plain JDBC persistence store.
 *
 * <p>The application owns the DataSource, connection pool, JDBC driver, and database version.</p>
 */
public class JdbcPersistenceStore extends BasePersistenceStore {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcPersistenceStore.class);
    private static final String DEFAULT_TABLE_NAME = "idempotency_records";

    private static final String SELECT_RECORD =
            "SELECT idempotency_key, status, expiry_timestamp, in_progress_expiry_timestamp, " +
                    "response_data, payload_hash FROM %s WHERE idempotency_key = ?";
    private static final String SELECT_RECORD_FOR_UPDATE =
            "SELECT idempotency_key, status, expiry_timestamp, in_progress_expiry_timestamp, " +
                    "response_data, payload_hash FROM %s WHERE idempotency_key = ? FOR UPDATE";
    private static final String INSERT_RECORD =
            "INSERT INTO %s (idempotency_key, status, expiry_timestamp, in_progress_expiry_timestamp, " +
                    "response_data, payload_hash) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_RECORD =
            "UPDATE %s SET status = ?, expiry_timestamp = ?, in_progress_expiry_timestamp = ?, " +
                    "response_data = ?, payload_hash = ? WHERE idempotency_key = ?";
    private static final String DELETE_RECORD =
            "DELETE FROM %s WHERE idempotency_key = ?";

    private final DataSource dataSource;
    private final String tableName;

    public JdbcPersistenceStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE_NAME);
    }

    public JdbcPersistenceStore(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName;
    }

    @Override
    public DataRecord getRecord(String idempotencyKey) throws IdempotencyItemNotFoundException {
        try (Connection connection = dataSource.getConnection()) {
            return selectRecord(connection, SELECT_RECORD, idempotencyKey);
        } catch (IdempotencyItemNotFoundException e) {
            throw e;
        } catch (SQLException e) {
            LOG.error("Failed to get record for idempotency key: {}", idempotencyKey, e);
            throw new IdempotencyItemNotFoundException(idempotencyKey);
        }
    }

    @Override
    public void putRecord(DataRecord record, Instant now) throws IdempotencyItemAlreadyExistsException {
        try (Connection connection = dataSource.getConnection()) {
            executeInTransaction(connection, () -> {
                DataRecord existing = selectRecordOrNull(connection, SELECT_RECORD_FOR_UPDATE,
                        record.getIdempotencyKey());

                if (existing != null) {
                    if (!existing.isExpired(now)) {
                        throw new IdempotencyItemAlreadyExistsException(
                                "Record already exists", null, existing);
                    }
                    updateRecord(connection, record);
                    return;
                }

                insertRecord(connection, record);
            });
        } catch (IdempotencyItemAlreadyExistsException e) {
            throw e;
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                throw new IdempotencyItemAlreadyExistsException("Record already exists", e, null);
            }
            LOG.error("Failed to put record for idempotency key: {}", record.getIdempotencyKey(), e);
            throw new IdempotencyItemAlreadyExistsException("Failed to put record", e, null);
        }
    }

    @Override
    public void updateRecord(DataRecord record) {
        try (Connection connection = dataSource.getConnection()) {
            executeInTransaction(connection, () -> {
                DataRecord existing = selectRecordOrNull(connection, SELECT_RECORD_FOR_UPDATE,
                        record.getIdempotencyKey());
                if (existing == null) {
                    LOG.warn("Record not found for update, idempotency key: {}. Update operation ignored.",
                            record.getIdempotencyKey());
                    return;
                }
                updateRecord(connection, record);
            });
        } catch (SQLException e) {
            LOG.error("Failed to update record for idempotency key: {}", record.getIdempotencyKey(), e);
            throw new RuntimeException("Failed to update record", e);
        }
    }

    @Override
    public void deleteRecord(String idempotencyKey) {
        try (Connection connection = dataSource.getConnection()) {
            executeInTransaction(connection, () -> {
                DataRecord existing = selectRecordOrNull(connection, SELECT_RECORD_FOR_UPDATE, idempotencyKey);
                if (existing == null) {
                    return;
                }
                try (PreparedStatement statement = connection.prepareStatement(sql(DELETE_RECORD))) {
                    statement.setString(1, idempotencyKey);
                    statement.executeUpdate();
                }
            });
        } catch (SQLException e) {
            LOG.error("Failed to delete record for idempotency key: {}", idempotencyKey, e);
            throw new RuntimeException("Failed to delete record", e);
        }
    }

    private DataRecord selectRecord(Connection connection, String sqlTemplate, String idempotencyKey)
            throws SQLException, IdempotencyItemNotFoundException {
        DataRecord record = selectRecordOrNull(connection, sqlTemplate, idempotencyKey);
        if (record == null) {
            throw new IdempotencyItemNotFoundException(idempotencyKey);
        }
        return record;
    }

    private DataRecord selectRecordOrNull(Connection connection, String sqlTemplate, String idempotencyKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql(sqlTemplate))) {
            statement.setString(1, idempotencyKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapRecord(rs);
            }
        }
    }

    private void insertRecord(Connection connection, DataRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql(INSERT_RECORD))) {
            bindRecord(statement, record);
            statement.executeUpdate();
        }
    }

    private void updateRecord(Connection connection, DataRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql(UPDATE_RECORD))) {
            statement.setString(1, record.getStatus().toString());
            statement.setLong(2, record.getExpiryTimestamp());
            statement.setLong(3, record.getInProgressExpiryTimestamp().orElse(0L));
            statement.setString(4, record.getResponseData());
            statement.setString(5, record.getPayloadHash());
            statement.setString(6, record.getIdempotencyKey());
            statement.executeUpdate();
        }
    }

    private void bindRecord(PreparedStatement statement, DataRecord record) throws SQLException {
        statement.setString(1, record.getIdempotencyKey());
        statement.setString(2, record.getStatus().toString());
        statement.setLong(3, record.getExpiryTimestamp());
        statement.setLong(4, record.getInProgressExpiryTimestamp().orElse(0L));
        statement.setString(5, record.getResponseData());
        statement.setString(6, record.getPayloadHash());
    }

    private DataRecord mapRecord(ResultSet rs) throws SQLException {
        long inProgressExpiryTimestamp = rs.getLong("in_progress_expiry_timestamp");
        OptionalLong inProgressExpiry = inProgressExpiryTimestamp > 0
                ? OptionalLong.of(inProgressExpiryTimestamp)
                : OptionalLong.empty();

        return new DataRecord(
                rs.getString("idempotency_key"),
                DataRecordStatus.valueOf(rs.getString("status")),
                rs.getLong("expiry_timestamp"),
                rs.getString("response_data"),
                rs.getString("payload_hash"),
                inProgressExpiry);
    }

    private void executeInTransaction(Connection connection, TransactionalSql action)
            throws SQLException, IdempotencyItemAlreadyExistsException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            action.execute();
            connection.commit();
        } catch (SQLException | IdempotencyItemAlreadyExistsException e) {
            rollback(connection);
            throw e;
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            LOG.warn("Failed to rollback JDBC transaction", rollbackException);
        }
    }

    private boolean isDuplicateKey(SQLException e) {
        return "23505".equals(e.getSQLState())
                || "23000".equals(e.getSQLState())
                || e.getErrorCode() == 1062;
    }

    private String sql(String sqlTemplate) {
        return String.format(sqlTemplate, tableName);
    }

    @FunctionalInterface
    private interface TransactionalSql {
        void execute() throws SQLException, IdempotencyItemAlreadyExistsException;
    }
}

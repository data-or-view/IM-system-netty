package com.wzg.idempotency.persistence;

import com.wzg.idempotency.exception.IdempotencyItemAlreadyExistsException;
import com.wzg.idempotency.exception.IdempotencyItemNotFoundException;
import com.wzg.idempotency.persistence.mybatis.IdempotencyRecordEntity;
import com.wzg.idempotency.persistence.mybatis.IdempotencyRecordMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * MyBatis-Plus persistence store for applications that already own a SqlSessionFactory.
 */
public class MyBatisPlusPersistenceStore extends BasePersistenceStore {
    private static final Logger LOG = LoggerFactory.getLogger(MyBatisPlusPersistenceStore.class);

    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisPlusPersistenceStore(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
        registerMapperIfNeeded(sqlSessionFactory);
    }

    @Override
    public DataRecord getRecord(String idempotencyKey) throws IdempotencyItemNotFoundException {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            IdempotencyRecordEntity entity = session.getMapper(IdempotencyRecordMapper.class)
                    .selectByKey(idempotencyKey);
            if (entity == null) {
                throw new IdempotencyItemNotFoundException(idempotencyKey);
            }
            return toRecord(entity);
        } catch (IdempotencyItemNotFoundException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to get record for idempotency key: {}", idempotencyKey, e);
            throw new IdempotencyItemNotFoundException(idempotencyKey);
        }
    }

    @Override
    public void putRecord(DataRecord record, Instant now) throws IdempotencyItemAlreadyExistsException {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            IdempotencyRecordMapper mapper = session.getMapper(IdempotencyRecordMapper.class);
            IdempotencyRecordEntity existing = mapper.selectByKeyForUpdate(record.getIdempotencyKey());

            if (existing != null) {
                DataRecord existingRecord = toRecord(existing);
                if (!existingRecord.isExpired(now)) {
                    throw new IdempotencyItemAlreadyExistsException(
                            "Record already exists", null, existingRecord);
                }
                mapper.updateRecord(toEntity(record, existing.getCreatedAt(), now.toEpochMilli()));
                session.commit();
                return;
            }

            mapper.insertRecord(toEntity(record, now.toEpochMilli(), now.toEpochMilli()));
            session.commit();
        } catch (IdempotencyItemAlreadyExistsException e) {
            throw e;
        } catch (Exception e) {
            if (isDuplicateKey(e)) {
                throw new IdempotencyItemAlreadyExistsException("Record already exists", e, null);
            }
            LOG.error("Failed to put record for idempotency key: {}", record.getIdempotencyKey(), e);
            throw new IdempotencyItemAlreadyExistsException("Failed to put record", e, null);
        }
    }

    @Override
    public void updateRecord(DataRecord record) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            IdempotencyRecordMapper mapper = session.getMapper(IdempotencyRecordMapper.class);
            IdempotencyRecordEntity existing = mapper.selectByKeyForUpdate(record.getIdempotencyKey());
            if (existing == null) {
                LOG.warn("Record not found for update, idempotency key: {}. Update operation ignored.",
                        record.getIdempotencyKey());
                session.commit();
                return;
            }

            mapper.updateRecord(toEntity(record, existing.getCreatedAt(), Instant.now().toEpochMilli()));
            session.commit();
        } catch (Exception e) {
            LOG.error("Failed to update record for idempotency key: {}", record.getIdempotencyKey(), e);
            throw new RuntimeException("Failed to update record", e);
        }
    }

    @Override
    public void deleteRecord(String idempotencyKey) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            IdempotencyRecordMapper mapper = session.getMapper(IdempotencyRecordMapper.class);
            IdempotencyRecordEntity existing = mapper.selectByKeyForUpdate(idempotencyKey);
            if (existing != null) {
                mapper.deleteByKey(idempotencyKey);
            }
            session.commit();
        } catch (Exception e) {
            LOG.error("Failed to delete record for idempotency key: {}", idempotencyKey, e);
            throw new RuntimeException("Failed to delete record", e);
        }
    }

    private static void registerMapperIfNeeded(SqlSessionFactory sqlSessionFactory) {
        org.apache.ibatis.session.Configuration configuration = sqlSessionFactory.getConfiguration();
        if (!configuration.hasMapper(IdempotencyRecordMapper.class)) {
            configuration.addMapper(IdempotencyRecordMapper.class);
        }
    }

    private static IdempotencyRecordEntity toEntity(DataRecord record, long createdAt, long updatedAt) {
        IdempotencyRecordEntity entity = new IdempotencyRecordEntity();
        entity.setIdempotencyKey(record.getIdempotencyKey());
        entity.setStatus(record.getStatus().toString());
        entity.setExpiryTimestamp(record.getExpiryTimestamp());
        entity.setInProgressExpiryTimestamp(record.getInProgressExpiryTimestamp().orElse(0L));
        entity.setResponseData(record.getResponseData());
        entity.setPayloadHash(record.getPayloadHash());
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);
        return entity;
    }

    private static DataRecord toRecord(IdempotencyRecordEntity entity) {
        OptionalLong inProgressExpiry = entity.getInProgressExpiryTimestamp() > 0
                ? OptionalLong.of(entity.getInProgressExpiryTimestamp())
                : OptionalLong.empty();

        return new DataRecord(
                entity.getIdempotencyKey(),
                DataRecordStatus.valueOf(entity.getStatus()),
                entity.getExpiryTimestamp(),
                entity.getResponseData(),
                entity.getPayloadHash(),
                inProgressExpiry);
    }

    private static boolean isDuplicateKey(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlException
                    && ("23505".equals(sqlException.getSQLState())
                    || "23000".equals(sqlException.getSQLState())
                    || sqlException.getErrorCode() == 1062)) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
        }
        return false;
    }
}

package com.im.core.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.common.exception.PersistenceExceptions;
import com.im.core.redis.RedisConfiguration;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;

import java.util.concurrent.TimeUnit;

public class RedisUploadSessionStore implements UploadSessionStore {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();
    private static final String FILE_PREFIX = "im:file:upload:file:";
    private static final String UPLOAD_PREFIX = "im:file:upload:multipart:";
    private static final long SESSION_TTL_SECONDS = 24 * 60 * 60;
    private static final long REDIS_TIMEOUT_MS = 3000;

    private final RedisClusterAsyncCommands<String, String> async;

    public RedisUploadSessionStore(RedisConfiguration redisConfig) {
        this.async = redisConfig.async();
    }

    @Override
    public void save(UploadSession session) {
        try {
            String json = MAPPER.writeValueAsString(session);
            async.setex(fileKey(session.fileId()), SESSION_TTL_SECONDS, json).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (session.uploadId() != null && !session.uploadId().isBlank()) {
                async.setex(uploadKey(session.uploadId()), SESSION_TTL_SECONDS, json).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            throw PersistenceExceptions.redis("save upload session", e);
        }
    }

    @Override
    public UploadSession getByFileId(String fileId) {
        return get(fileKey(fileId));
    }

    @Override
    public UploadSession getByUploadId(String uploadId) {
        return get(uploadKey(uploadId));
    }

    @Override
    public void delete(UploadSession session) {
        try {
            async.del(fileKey(session.fileId())).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (session.uploadId() != null && !session.uploadId().isBlank()) {
                async.del(uploadKey(session.uploadId())).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            throw PersistenceExceptions.redis("delete upload session", e);
        }
    }

    private UploadSession get(String key) {
        try {
            String json = async.get(key).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return json == null ? null : MAPPER.readValue(json, UploadSession.class);
        } catch (Exception e) {
            throw PersistenceExceptions.redis("get upload session", e);
        }
    }

    private static String fileKey(String fileId) {
        return FILE_PREFIX + fileId;
    }

    private static String uploadKey(String uploadId) {
        return UPLOAD_PREFIX + uploadId;
    }
}

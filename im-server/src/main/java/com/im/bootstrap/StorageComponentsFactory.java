package com.im.bootstrap;

import com.im.api.BusinessMessageDlqStore;
import com.im.api.IFileStorageService;
import com.im.api.IGroupMessageStore;
import com.im.api.IMessageQueue;
import com.im.api.IMessageStore;
import com.im.api.ISequenceManager;
import com.im.api.ISingleMessageStore;
import com.im.api.ISystemMessageStore;
import com.im.api.SendMessageIdempotency;
import com.im.common.retry.RetryExecutor;
import com.im.config.Config;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.file.DbFileObjectMetadataStore;
import com.im.core.file.DirectFileTransferUseCase;
import com.im.core.file.RedisUploadSessionStore;
import com.im.core.mq.RedisMessageQueue;
import com.im.core.redis.RedisConfiguration;
import com.im.core.reliability.DbBusinessMessageDlqStore;
import com.im.core.reliability.WzgSendMessageIdempotency;
import com.im.core.seq.RedisSequenceManager;
import com.im.core.store.DbMessageStore;
import com.im.core.store.GroupMessageStoreAdapter;
import com.im.core.store.SingleMessageStoreAdapter;
import com.im.core.system.DbSystemMessageStore;
import com.im.infrastructure.message.rocketmq.RocketMqMessageQueue;
import com.im.infrastructure.storage.file.MinioFileStorageService;
import com.wzg.idempotency.persistence.MyBatisPlusPersistenceStore;

import java.time.Duration;
import java.util.Locale;

final class StorageComponentsFactory {

    private StorageComponentsFactory() {
    }

    static ServerComponentsFactory.StorageDependencies createStorage(Config config,
                                                                     RedisConfiguration redisConfig,
                                                                     String nodeId,
                                                                     RetryExecutor retryExecutor) {
        ISequenceManager sequenceManager = new RedisSequenceManager(redisConfig);
        IMessageStore messageStore = new DbMessageStore(retryExecutor);
        ISingleMessageStore singleMessageStore = new SingleMessageStoreAdapter(messageStore);
        IGroupMessageStore groupMessageStore = new GroupMessageStoreAdapter(messageStore);
        IMessageQueue messageQueue = createMessageQueue(config, redisConfig, nodeId);
        SendMessageIdempotency sendMessageIdempotency = new WzgSendMessageIdempotency(
                new MyBatisPlusPersistenceStore(MyBatisPlusFactory.getSqlSessionFactory()),
                Duration.ofSeconds(config.getLong("im.idempotency.send-expire-seconds", 24 * 60 * 60L)));
        BusinessMessageDlqStore businessMessageDlqStore = new DbBusinessMessageDlqStore();
        String minioAccessKey = config.getString("im.minio.access-key")
                .orElse(BootstrapSecurityChecks.DEFAULT_MINIO_ACCESS_KEY);
        String minioSecretKey = config.getString("im.minio.secret-key")
                .orElse(BootstrapSecurityChecks.DEFAULT_MINIO_SECRET_KEY);
        BootstrapSecurityChecks.requireSafeSecret(config, "im.minio.access-key", minioAccessKey,
                BootstrapSecurityChecks.DEFAULT_MINIO_ACCESS_KEY);
        BootstrapSecurityChecks.requireSafeSecret(config, "im.minio.secret-key", minioSecretKey,
                BootstrapSecurityChecks.DEFAULT_MINIO_SECRET_KEY);
        IFileStorageService fileStorage = new MinioFileStorageService(
                config.getString("im.minio.endpoint").orElse("http://127.0.0.1:9000"),
                minioAccessKey,
                minioSecretKey);
        String fileBucket = config.getString("im.minio.bucket").orElse("im-system");
        DirectFileTransferUseCase directFileTransferUseCase = new DirectFileTransferUseCase(
                fileStorage,
                new RedisUploadSessionStore(redisConfig),
                new DbFileObjectMetadataStore(fileBucket),
                fileBucket,
                config.getInt("im.minio.presign-expire-seconds", 900),
                config.getLong("im.minio.max-file-size", 100L * 1024 * 1024));
        ISystemMessageStore systemMessageStore = new DbSystemMessageStore();
        return new ServerComponentsFactory.StorageDependencies(
                sequenceManager, messageStore, singleMessageStore, groupMessageStore, messageQueue,
                sendMessageIdempotency, businessMessageDlqStore,
                fileStorage, directFileTransferUseCase, systemMessageStore);
    }

    static IMessageQueue createMessageQueue(Config config, RedisConfiguration redisConfig, String nodeId) {
        String type = config.getString("im.mq.type", "redis").trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "redis", "redis-streams" -> new RedisMessageQueue(redisConfig, nodeId);
            case "rocketmq" -> new RocketMqMessageQueue(config, nodeId);
            default -> throw new IllegalArgumentException("Unsupported im.mq.type: " + type);
        };
    }
}

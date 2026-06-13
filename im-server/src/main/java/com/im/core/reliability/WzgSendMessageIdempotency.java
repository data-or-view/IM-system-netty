package com.im.core.reliability;

import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ConflictException;
import com.im.common.exception.InfrastructureException;
import com.im.common.exception.ValidationException;
import com.wzg.idempotency.Idempotency;
import com.wzg.idempotency.config.IdempotencyConfig;
import com.wzg.idempotency.core.ExecutionContext;
import com.wzg.idempotency.exception.IdempotencyAlreadyInProgressException;
import com.wzg.idempotency.exception.IdempotencyConfigurationException;
import com.wzg.idempotency.exception.IdempotencyInconsistentStateException;
import com.wzg.idempotency.exception.IdempotencyKeyException;
import com.wzg.idempotency.exception.IdempotencyPersistenceLayerException;
import com.wzg.idempotency.exception.IdempotencyValidationException;
import com.wzg.idempotency.persistence.BasePersistenceStore;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.function.Supplier;

public final class WzgSendMessageIdempotency implements SendMessageIdempotency {

    private static final String FUNCTION_NAME = "im-send-message";
    private static final Object CONFIG_LOCK = new Object();
    private static volatile boolean configured;

    public WzgSendMessageIdempotency(BasePersistenceStore persistenceStore, Duration expiration) {
        synchronized (CONFIG_LOCK) {
            if (!configured) {
                Idempotency.config()
                        .withPersistenceStore(persistenceStore)
                        .withConfig(IdempotencyConfig.builder()
                                .withUseLocalCache(false)
                                .withThrowOnNoIdempotencyKey(true)
                                .withExpiration(expiration)
                                .build())
                        .configure();
                configured = true;
            }
        }
    }

    @Override
    public <T> T execute(String idempotencyKey, Supplier<T> action, Class<T> returnType) {
        try {
            Idempotency.registerExecutionContext(new SendExecutionContext());
            return Idempotency.makeIdempotent(FUNCTION_NAME, idempotencyKey, action, returnType);
        } catch (IdempotencyAlreadyInProgressException e) {
            throw new ConflictException("message send is still processing: " + idempotencyKey, e);
        } catch (IdempotencyKeyException | IdempotencyValidationException e) {
            throw new ValidationException("invalid message idempotency key", e);
        } catch (IdempotencyPersistenceLayerException
                 | IdempotencyConfigurationException
                 | IdempotencyInconsistentStateException e) {
            throw new InfrastructureException(ImErrorCode.INTERNAL_ERROR, "message idempotency unavailable", e);
        }
    }

    private static final class SendExecutionContext implements ExecutionContext {
        @Override
        public OptionalInt getRemainingTimeInMillis() {
            return OptionalInt.of(30_000);
        }

        @Override
        public String getFunctionName() {
            return FUNCTION_NAME;
        }
    }
}

package com.im.core.reliability;

import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.InfrastructureException;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import com.im.core.observability.MessageObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReliableMessageHandler implements IMessageQueue.MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ReliableMessageHandler.class);

    private final String topic;
    private final IMessageQueue.MessageHandler delegate;
    private final RetryExecutor retryExecutor;
    private final SendMessageIdempotency idempotency;
    private final SendMessageFailureStore failureStore;

    public ReliableMessageHandler(String topic,
                                  IMessageQueue.MessageHandler delegate,
                                  RetryExecutor retryExecutor,
                                  SendMessageIdempotency idempotency,
                                  SendMessageFailureStore failureStore) {
        this.topic = topic;
        this.delegate = delegate;
        this.retryExecutor = retryExecutor;
        this.idempotency = idempotency != null ? idempotency : SendMessageIdempotency.none();
        this.failureStore = failureStore != null ? failureStore : SendMessageFailureStore.none();
    }

    @Override
    public void onMessage(Message msg) {
        String key = idempotencyKey(msg);
        try (MessageObservability.Scope ignored = MessageObservability.bind(topic, msg)) {
            log.debug("Consuming message with reliability guard: fields={}, idempotencyKey={}",
                    MessageObservability.fields(topic, msg), key);
            idempotency.execute(key, () -> {
                retryExecutor.execute(RetryStrategies.MQ_CONSUME, () -> {
                    delegate.onMessage(msg);
                    return null;
                });
                return "OK";
            }, String.class);
        } catch (RuntimeException processingFailure) {
            recordDeadLetter(msg, processingFailure);
        }
    }

    private void recordDeadLetter(Message msg, RuntimeException processingFailure) {
        try {
            failureStore.recordFailure(topic, msg, processingFailure);
            log.error("Message consumed to dead-letter table: fields={}, causeType={}, causeMessage={}",
                    MessageObservability.fields(topic, msg),
                    processingFailure.getClass().getName(),
                    processingFailure.getMessage(),
                    processingFailure);
        } catch (RuntimeException recordFailure) {
            throw new InfrastructureException(ImErrorCode.INTERNAL_ERROR,
                    "consumer failed and dead-letter record failed", recordFailure);
        }
    }

    private String idempotencyKey(Message msg) {
        String messageId = msg != null ? msg.getMessageId() : "";
        String conversationId = msg != null ? msg.getConversationId() : "";
        return "consume:" + topic + ":" + conversationId + ":" + messageId;
    }
}

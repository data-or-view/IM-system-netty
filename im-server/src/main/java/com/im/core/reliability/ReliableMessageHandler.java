package com.im.core.reliability;

import com.im.api.Message;
import com.im.api.QueueMessageHandler;
import com.im.api.BusinessMessageDlqStore;
import com.im.api.SendMessageIdempotency;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.InfrastructureException;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import com.im.core.observability.MessageObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReliableMessageHandler implements QueueMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ReliableMessageHandler.class);

    private final String topic;
    private final QueueMessageHandler delegate;
    private final RetryExecutor retryExecutor;
    private final SendMessageIdempotency idempotency;
    private final BusinessMessageDlqStore failureStore;

    public ReliableMessageHandler(String topic,
                                  QueueMessageHandler delegate,
                                  RetryExecutor retryExecutor,
                                  SendMessageIdempotency idempotency,
                                  BusinessMessageDlqStore failureStore) {
        this.topic = topic;
        this.delegate = delegate;
        this.retryExecutor = retryExecutor;
        this.idempotency = idempotency != null ? idempotency : SendMessageIdempotency.none();
        this.failureStore = failureStore != null ? failureStore : BusinessMessageDlqStore.none();
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
            recordBusinessDlq(msg, processingFailure);
        }
    }

    private void recordBusinessDlq(Message msg, RuntimeException processingFailure) {
        try {
            failureStore.recordFailure(topic, msg, processingFailure);
            log.error("Message consumed to business-DLQ table: fields={}, causeType={}, causeMessage={}",
                    MessageObservability.fields(topic, msg),
                    processingFailure.getClass().getName(),
                    processingFailure.getMessage(),
                    processingFailure);
        } catch (RuntimeException recordFailure) {
            throw new InfrastructureException(ImErrorCode.INTERNAL_ERROR,
                    "consumer failed and business-DLQ record failed", recordFailure);
        }
    }

    private String idempotencyKey(Message msg) {
        String messageId = msg != null ? msg.getMessageId() : "";
        String conversationId = msg != null ? msg.getConversationId() : "";
        return "consume:" + topic + ":" + conversationId + ":" + messageId;
    }
}

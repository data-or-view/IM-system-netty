package com.im.core.reliability;

import com.im.api.Message;
import com.im.api.QueueMessageHandler;
import com.im.api.BusinessMessageDlqStore;
import com.im.api.SendMessageIdempotency;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.InfrastructureException;
import com.im.common.retry.RetryExecutor;
import com.im.common.retry.RetryStrategies;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.MessageObservability;
import com.im.core.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

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
            log.info(StructuredLog.event(LogEvents.MQ_CONSUME_STARTED,
                    fieldsWithIdempotency(msg, key)));
            idempotency.execute(key, () -> {
                retryExecutor.execute(RetryStrategies.MQ_CONSUME, () -> {
                    delegate.onMessage(msg);
                    return null;
                });
                return "OK";
            }, String.class);
            log.info(StructuredLog.event(LogEvents.MQ_CONSUME_SUCCEEDED,
                    fieldsWithIdempotency(msg, key)));
        } catch (RuntimeException processingFailure) {
            recordBusinessDlq(msg, processingFailure);
        }
    }

    private void recordBusinessDlq(Message msg, RuntimeException processingFailure) {
        try {
            failureStore.recordFailure(topic, msg, processingFailure);
            Map<String, Object> fields = fieldsWithIdempotency(msg, idempotencyKey(msg));
            fields.put(LogFields.EXCEPTION_CLASS, processingFailure.getClass().getSimpleName());
            log.error(StructuredLog.event(LogEvents.MQ_CONSUME_FAILED, fields), processingFailure);
        } catch (RuntimeException recordFailure) {
            throw new InfrastructureException(ImErrorCode.INTERNAL_ERROR,
                    "consumer failed and business-DLQ record failed", recordFailure);
        }
    }

    private Map<String, Object> fieldsWithIdempotency(Message msg, String key) {
        Map<String, Object> fields = new LinkedHashMap<>(MessageObservability.fields(topic, msg));
        fields.put("idempotencyKey", key);
        return fields;
    }

    private String idempotencyKey(Message msg) {
        String messageId = msg != null ? msg.getMessageId() : "";
        String conversationId = msg != null ? msg.getConversationId() : "";
        return "consume:" + topic + ":" + conversationId + ":" + messageId;
    }
}

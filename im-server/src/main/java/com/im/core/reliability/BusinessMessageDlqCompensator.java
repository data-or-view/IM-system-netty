package com.im.core.reliability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.BusinessMessageDlqRecord;
import com.im.api.BusinessMessageDlqStore;
import com.im.common.lifecycle.Lifecycle;
import com.im.common.util.IMExecutors;
import com.im.core.observability.MessageObservability;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class BusinessMessageDlqCompensator implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(BusinessMessageDlqCompensator.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private final IMessageQueue messageQueue;
    private final BusinessMessageDlqStore failureStore;
    private final int batchSize;
    private final int maxAttempts;
    private final long idleIntervalMs;
    private final long baseDelayMs;
    private final long claimLeaseMs;
    private volatile boolean running;
    private volatile Thread worker;

    public BusinessMessageDlqCompensator(IMessageQueue messageQueue,
                                     BusinessMessageDlqStore failureStore,
                                     int batchSize,
                                     int maxAttempts,
                                     long idleIntervalMs,
                                     long baseDelayMs) {
        this(messageQueue, failureStore, batchSize, maxAttempts, idleIntervalMs, baseDelayMs, 30_000L);
    }

    public BusinessMessageDlqCompensator(IMessageQueue messageQueue,
                                     BusinessMessageDlqStore failureStore,
                                     int batchSize,
                                     int maxAttempts,
                                     long idleIntervalMs,
                                     long baseDelayMs,
                                     long claimLeaseMs) {
        this.messageQueue = messageQueue;
        this.failureStore = failureStore;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.idleIntervalMs = Math.max(200, idleIntervalMs);
        this.baseDelayMs = Math.max(200, baseDelayMs);
        this.claimLeaseMs = Math.max(1_000, claimLeaseMs);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = IMExecutors.startVirtualThread("business-message-dlq-compensator", this::runLoop);
        log.info("BusinessMessageDlqCompensator started: batchSize={}, maxAttempts={}", batchSize, maxAttempts);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        log.info("BusinessMessageDlqCompensator stopped");
    }

    private void runLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                int republished = republishDueDlqMessages();
                if (republished == 0) {
                    sleep();
                }
            } catch (Exception e) {
                log.error("Business message DLQ compensation loop failed: {}", e.getMessage(), e);
                sleep();
            }
        }
    }

    int republishDueDlqMessages() {
        int republished = 0;
        long now = System.currentTimeMillis();
        for (BusinessMessageDlqRecord record : failureStore.claimDueFailures(now, batchSize, claimLeaseMs)) {
            republishOne(record);
            republished++;
        }
        return republished;
    }

    private void republishOne(BusinessMessageDlqRecord record) {
        Message message = null;
        try {
            message = deserialize(record.payloadJson());
            try (MessageObservability.Scope ignored = MessageObservability.bindBusinessDlq(record.topic(), record.id(), message)) {
                log.info("Republishing business-DLQ message: id={}, attempt={}, fields={}",
                        record.id(), record.attemptCount() + 1, MessageObservability.fields(record.topic(), message));
            }
            messageQueue.publish(record.topic(), message);
            failureStore.markRepublished(record.id());
            try (MessageObservability.Scope ignored = MessageObservability.bindBusinessDlq(record.topic(), record.id(), message)) {
                log.info("Republished business-DLQ message: id={}, fields={}",
                        record.id(), MessageObservability.fields(record.topic(), message));
            }
        } catch (Exception e) {
            int nextAttempt = record.attemptCount() + 1;
            if (nextAttempt >= maxAttempts) {
                failureStore.markFailed(record.id(), nextAttempt, e);
                try (MessageObservability.Scope ignored = MessageObservability.bindBusinessDlq(record.topic(), record.id(), message)) {
                    log.error("Business-DLQ republish exhausted: id={}, attempt={}, fields={}, causeType={}, causeMessage={}",
                            record.id(), nextAttempt, MessageObservability.fields(record.topic(), message),
                            e.getClass().getName(), e.getMessage(), e);
                }
                return;
            }
            failureStore.markRetryLater(record.id(), nextAttempt, nextRetryAt(nextAttempt), e);
            try (MessageObservability.Scope ignored = MessageObservability.bindBusinessDlq(record.topic(), record.id(), message)) {
                log.warn("Business-DLQ republish postponed: id={}, attempt={}, fields={}, causeType={}, causeMessage={}",
                        record.id(), nextAttempt, MessageObservability.fields(record.topic(), message),
                        e.getClass().getName(), e.getMessage());
            }
        }
    }

    private static Message deserialize(String payloadJson) throws Exception {
        Map<String, Object> map = MAPPER.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        return Message.fromJsonMap(map);
    }

    private long nextRetryAt(int attemptCount) {
        long delay = Math.min(baseDelayMs * (1L << Math.min(attemptCount, 10)), 60_000L);
        return System.currentTimeMillis() + delay;
    }

    private void sleep() {
        try {
            Thread.sleep(idleIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}

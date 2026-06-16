package com.im.core.reliability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.MessageSendFailureRecord;
import com.im.api.SendMessageFailureStore;
import com.im.common.lifecycle.Lifecycle;
import com.im.common.util.IMExecutors;
import com.im.core.observability.MessageObservability;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class MessageFailureCompensator implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(MessageFailureCompensator.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private final IMessageQueue messageQueue;
    private final SendMessageFailureStore failureStore;
    private final int batchSize;
    private final int maxAttempts;
    private final long idleIntervalMs;
    private final long baseDelayMs;
    private volatile boolean running;
    private volatile Thread worker;

    public MessageFailureCompensator(IMessageQueue messageQueue,
                                     SendMessageFailureStore failureStore,
                                     int batchSize,
                                     int maxAttempts,
                                     long idleIntervalMs,
                                     long baseDelayMs) {
        this.messageQueue = messageQueue;
        this.failureStore = failureStore;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.idleIntervalMs = Math.max(200, idleIntervalMs);
        this.baseDelayMs = Math.max(200, baseDelayMs);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = IMExecutors.startVirtualThread("message-failure-compensator", this::runLoop);
        log.info("MessageFailureCompensator started: batchSize={}, maxAttempts={}", batchSize, maxAttempts);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        log.info("MessageFailureCompensator stopped");
    }

    private void runLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                int replayed = replayDueFailures();
                if (replayed == 0) {
                    sleep();
                }
            } catch (Exception e) {
                log.error("Message failure compensation loop failed: {}", e.getMessage(), e);
                sleep();
            }
        }
    }

    int replayDueFailures() {
        int replayed = 0;
        long now = System.currentTimeMillis();
        for (MessageSendFailureRecord record : failureStore.findDueFailures(now, batchSize)) {
            replayOne(record);
            replayed++;
        }
        return replayed;
    }

    private void replayOne(MessageSendFailureRecord record) {
        Message message = null;
        try {
            message = deserialize(record.payloadJson());
            try (MessageObservability.Scope ignored = MessageObservability.bindDeadLetter(record.topic(), record.id(), message)) {
                log.info("Replaying failed message: id={}, attempt={}, fields={}",
                        record.id(), record.attemptCount() + 1, MessageObservability.fields(record.topic(), message));
            }
            messageQueue.publishAsync(record.topic(), message);
            failureStore.markReplayed(record.id());
            try (MessageObservability.Scope ignored = MessageObservability.bindDeadLetter(record.topic(), record.id(), message)) {
                log.info("Replayed failed message: id={}, fields={}",
                        record.id(), MessageObservability.fields(record.topic(), message));
            }
        } catch (Exception e) {
            int nextAttempt = record.attemptCount() + 1;
            if (nextAttempt >= maxAttempts) {
                failureStore.markFailed(record.id(), nextAttempt, e);
                try (MessageObservability.Scope ignored = MessageObservability.bindDeadLetter(record.topic(), record.id(), message)) {
                    log.error("Failed message replay exhausted: id={}, attempt={}, fields={}, causeType={}, causeMessage={}",
                            record.id(), nextAttempt, MessageObservability.fields(record.topic(), message),
                            e.getClass().getName(), e.getMessage(), e);
                }
                return;
            }
            failureStore.markRetryLater(record.id(), nextAttempt, nextRetryAt(nextAttempt), e);
            try (MessageObservability.Scope ignored = MessageObservability.bindDeadLetter(record.topic(), record.id(), message)) {
                log.warn("Failed message replay postponed: id={}, attempt={}, fields={}, causeType={}, causeMessage={}",
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

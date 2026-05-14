package com.im.core.usecase;

import com.im.api.IMCommand;
import com.im.api.IMessageStore;
import com.im.api.ISequenceManager;

import java.util.List;

public class PullMessageUseCase {

    private final IMessageStore messageStore;
    private final ISequenceManager sequenceManager;

    public PullMessageUseCase(IMessageStore messageStore, ISequenceManager sequenceManager) {
        this.messageStore = messageStore;
        this.sequenceManager = sequenceManager;
    }

    public record PullMessageResult(List<IMCommand> messages, long maxSeq) {}

    public PullMessageResult execute(String conversationId, long startSeq, long endSeq, int limit) {
        List<IMCommand> messages = messageStore.pullBySequence(conversationId, startSeq, endSeq, limit);
        long maxSeq = 0;
        if (sequenceManager != null) {
            maxSeq = sequenceManager.getMaximumSequence(conversationId);
        }
        return new PullMessageResult(messages, maxSeq);
    }
}

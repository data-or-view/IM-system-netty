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
import com.im.core.file.DirectFileTransferUseCase;

record StorageDependencies(ISequenceManager sequenceManager,
                           IMessageStore messageStore,
                           ISingleMessageStore singleMessageStore,
                           IGroupMessageStore groupMessageStore,
                           IMessageQueue messageQueue,
                           SendMessageIdempotency sendMessageIdempotency,
                           BusinessMessageDlqStore businessMessageDlqStore,
                           IFileStorageService fileStorage,
                           DirectFileTransferUseCase directFileTransferUseCase,
                           ISystemMessageStore systemMessageStore) {
}

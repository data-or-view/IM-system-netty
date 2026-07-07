package com.im.bootstrap;

import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.core.session.SessionManager;

import java.util.concurrent.ExecutorService;

record RuntimeDependencies(SessionManager sessionManager,
                           PendingAcknowledgementManager pendingAcknowledgementManager,
                           ExecutorService virtualExecutor,
                           RuntimeFriendApplyNotifier friendApplyNotifier,
                           RuntimeGroupApplyNotifier groupApplyNotifier,
                           RuntimeSystemMessageNotifier systemMessageNotifier,
                           RuntimeMessageRevokeNotifier messageRevokeNotifier) {
}

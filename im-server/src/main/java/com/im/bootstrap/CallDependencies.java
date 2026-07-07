package com.im.bootstrap;

import com.im.api.ICallManager;
import com.im.core.call.CallStateManager;
import com.im.core.call.GroupCallManager;

record CallDependencies(ICallManager callManager,
                        CallStateManager callStateManager,
                        GroupCallManager groupCallManager) {
}

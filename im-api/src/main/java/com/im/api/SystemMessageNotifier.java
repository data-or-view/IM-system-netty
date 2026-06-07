package com.im.api;

import java.util.List;

public interface SystemMessageNotifier {

    SystemMessageNotifier NOOP = (userIds, summary) -> { };

    void notify(List<String> userIds, SystemMessageSummary summary);
}

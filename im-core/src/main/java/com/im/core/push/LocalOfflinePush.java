package com.im.core.push;

import com.im.api.IOfflinePush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地离线推送（占位 no-op）。
 *
 * 生产环境请换 FcmPush / ApnsPush / HwPush / 个推等实现。
 */
public class LocalOfflinePush implements IOfflinePush {

    private static final Logger log = LoggerFactory.getLogger(LocalOfflinePush.class);

    @Override
    public void push(String userId, String title, String body,
                     String conversationId, String signalInfo) {
        log.debug("Offline push: userId={}, title={}, body={}, convId={}",
                userId, title, truncate(body), conversationId);
    }

    private static String truncate(String s) {
        return s != null && s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}

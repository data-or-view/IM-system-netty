package com.im.core.security;

import com.im.api.UserInformation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UserProfileSanitizer {

    private UserProfileSanitizer() {
    }

    public static Map<String, Object> self(UserInformation info) {
        Map<String, Object> view = publicView(info);
        view.put("ex", safe(info.getEx()));
        view.put("appMangerLevel", info.getAppMangerLevel());
        view.put("globalRecvMsgOpt", info.getGlobalRecvMsgOpt());
        view.put("createTime", info.getCreateTime());
        view.put("updatedAt", info.getUpdatedAt());
        return view;
    }

    public static Map<String, Object> friend(UserInformation info) {
        Map<String, Object> view = publicView(info);
        view.put("createTime", info.getCreateTime());
        return view;
    }

    public static Map<String, Object> publicView(UserInformation info) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", safe(info.getUserId()));
        view.put("nickname", safe(info.getNickname()));
        view.put("faceUrl", safe(info.getFaceUrl()));
        return view;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}

package com.im.core.call;

public record GroupCallJoinResult(GroupCallSession session,
                                  String token,
                                  String sfuEndpoint) {
}

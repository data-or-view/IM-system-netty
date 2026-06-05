package com.im.core.usecase;

import com.im.api.Message;
import java.util.List;

public record LoginResult(String token, String refreshToken, int platformId, List<Message> offlineMessages) {}

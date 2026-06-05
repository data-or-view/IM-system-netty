package com.im.bootstrap;

import com.im.config.Config;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.session.SessionManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

class TransportServerTest {

    @Test
    void startAndStopWhenHttpAndWebSocketAreDisabled() throws Exception {
        TransportServer transportServer = new TransportServer(
                new TestConfig(Map.of(
                        "im.ws.enabled", "false",
                        "im.http.enabled", "false")),
                new SessionManager(),
                null,
                new ApiDispatcher(),
                Executors.newVirtualThreadPerTaskExecutor());

        transportServer.start();
        transportServer.stop();
    }

    private record TestConfig(Map<String, String> values) implements Config {
        @Override
        public Optional<String> getString(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Optional<Integer> getInt(String key) {
            String value = values.get(key);
            if (value == null) return Optional.empty();
            return Optional.of(Integer.parseInt(value));
        }

        @Override
        public Optional<Long> getLong(String key) {
            String value = values.get(key);
            if (value == null) return Optional.empty();
            return Optional.of(Long.parseLong(value));
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            String value = values.get(key);
            if (value == null) return Optional.empty();
            return Optional.of(Boolean.parseBoolean(value));
        }

        @Override
        public Optional<Duration> getDuration(String key) {
            return Optional.empty();
        }

        @Override
        public boolean hasKey(String key) {
            return values.containsKey(key);
        }
    }
}

package com.im.infrastructure.message.rocketmq;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

final class RocketMqIntegrationSupport {

    private static final String DEFAULT_NAME_SERVER = "127.0.0.1:9876";

    private RocketMqIntegrationSupport() {
    }

    static RocketMqMessageQueueProperties isolatedProperties(String testName) {
        String suffix = Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toString(ThreadLocalRandom.current().nextInt(1_000_000), 36);
        return new RocketMqMessageQueueProperties(
                nameServer(),
                "im-it-producer-" + testName + "-" + suffix,
                "im-it-consumer-" + testName + "-" + suffix,
                "im-it-" + testName + "-" + suffix + "-",
                Duration.ofSeconds(3),
                1);
    }

    static String nameServer() {
        return System.getenv().getOrDefault("IM_ROCKETMQ_IT_NAME_SERVER", DEFAULT_NAME_SERVER);
    }

    static void assumeBrokerReachable() {
        String nameServer = nameServer();
        String[] hostPort = nameServer.split(":", 2);
        Assumptions.assumeTrue(hostPort.length == 2,
                "IM_ROCKETMQ_IT_NAME_SERVER must use host:port, actual=" + nameServer);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1])), 1500);
        } catch (IOException | NumberFormatException e) {
            Assumptions.abort("RocketMQ integration tests skipped because name-server is unreachable: "
                    + nameServer + " (" + e.getMessage() + ")");
        }
    }
}

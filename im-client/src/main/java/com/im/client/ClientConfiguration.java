package com.im.client;

/**
 * 客户端配置，参考 RocketMQ 的 ClientConfiguration。
 *
 * 核心参数：
 *   heartbeatIntervalSeconds = 32（略大于 Broker 端 30s 的心跳发送间隔，避免时序临界）
 *   loginTimeoutSeconds      = 10（连上后 10s 内必须登录成功）
 *   reconnectDelaySeconds    = 3（断线后等 3s 重连）
 */
public class ClientConfiguration {

    private String serverHost = "127.0.0.1";
    private int serverPort = 8080;
    private String userId;
    private int heartbeatIntervalSeconds = 30;
    private int heartbeatTimeoutSeconds = 120;
    private int loginTimeoutSeconds = 10;
    private int reconnectDelaySeconds = 3;

    // ========== Getters / Setters ==========

    public String getServerHost() { return serverHost; }
    public void setServerHost(String serverHost) { this.serverHost = serverHost; }

    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    public int getLoginTimeoutSeconds() { return loginTimeoutSeconds; }
    public void setLoginTimeoutSeconds(int loginTimeoutSeconds) {
        this.loginTimeoutSeconds = loginTimeoutSeconds;
    }

    public int getReconnectDelaySeconds() { return reconnectDelaySeconds; }
    public void setReconnectDelaySeconds(int reconnectDelaySeconds) {
        this.reconnectDelaySeconds = reconnectDelaySeconds;
    }

    public String getServerAddress() {
        return serverHost + ":" + serverPort;
    }
}

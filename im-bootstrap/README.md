# im-bootstrap — 启动引导层

Netty 双端口启动器，装配所有组件。

## 配置

`ServerConfiguration` 配置字段：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| host | 0.0.0.0 | 监听地址 |
| tcpPort | 8080 | TCP 端口 |
| wsPort | 8081 | WebSocket 端口 |
| wsEnabled | true | 是否启用 WebSocket |
| idleTimeSeconds | 30 | 空闲连接检测超时 |
| tokenSecret | - | Token 签名密钥 |
| tokenTtlDays | 30 | Token 有效期 |
| webhookUrl | "" | Webhook 地址（空=禁用） |

## 启动

```bash
java --enable-preview -jar target/im-bootstrap-1.0.0-SNAPSHOT.jar
```

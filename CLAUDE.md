# IM-system-netty

Single-process Netty-based IM server with WS/HTTP unified pipeline.

## OpenIM Reference

This project references OpenIM source code for design inspiration. The source is at:

- **Absolute**: `/Users/macbook/java/IdeaProjects/github-source/data-or-view/open-im-server/`
- **Relative**: `../open-im-server/`

When designing new features, evaluating architecture changes, or debugging message flow, invoke the `openim-reference` skill to look up how OpenIM implements the equivalent functionality.

## Project Structure

| Module | Path | Role |
|--------|------|------|
| `im-api` | `im-api/` | Public interfaces (IUserManager, IMessageQueue, IRouteTable, etc.) |
| `im-core` | `im-core/` | Use cases + dispatcher + 所有基础设施实现（DB, Redis, 存储, 推送等） |
| `im-adapter` | `im-adapter/` | WS/HTTP 协议适配层，handler |
| `im-bootstrap` | `im-bootstrap/` | Server boot, DI wiring, main() |
| `im-infrastructure-*` | `im-infrastructure/` | 通用基础设施（序列化、缓存接口、配置、消息队列封装） |

## Key Design Decisions

- Single-process JVM with virtual threads (Project Loom)
- WS and HTTP share the same ApiDispatcher pipeline
- Async persistence via PersistenceConsumer (Redis Streams → MySQL)
- Online status in Redis ZSet via IRouteTable
- Interfaces in im-api, implementations injected via constructor

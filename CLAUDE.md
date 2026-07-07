# IM-system-netty

Cluster-first full-stack IM system with a Java 21 Netty backend, React/Vite frontend, TypeScript SDK, and real-protocol scenario tests.

For current project facts, read these first:

1. `AGENTS.md` - AI coding constraints and cluster rules.
2. `docs/ai-project-guide.md` - deployment architecture, modules, startup scripts, config, message flow.
3. `ARCHITECTURE.md` - backend lifecycle, runtime composition, Redis/MySQL data model.
4. `im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java` - production composition root.
5. `im-api/src/main/java/com/im/api/Operation.java` - operation names, HTTP routes, auth boundary.

## OpenIM Reference

This project references OpenIM source code for design inspiration. The source is at:

- Absolute: `/Users/macbook/java/IdeaProjects/github-source/data-or-view/open-im-server/`
- Relative: `../open-im-server/`

When designing new IM features, evaluating architecture changes, or debugging message flow, use the `openim-reference` skill if available.

## Project Structure

| Module | Path | Role |
|--------|------|------|
| `im-api` | `im-api/` | Java interfaces, DTOs, `Operation`, protocol enums. |
| `im-server` | `im-server/` | Netty transport, dispatcher, handlers, use cases, Redis/MySQL/MQ/MinIO/LiveKit implementation. |
| `im-infrastructure-*` | `im-infrastructure/` | Config, cache, serialization, idempotency, object storage, and RocketMQ infrastructure. |
| `im-sdk` | `im-sdk/` | TypeScript SDK for WS/HTTP, token, reconnect sync, business APIs. |
| `im-web` | `im-web/` | React/Vite chat workspace using `im-sdk`. |
| `im-scenario-tests` | `im-scenario-tests/` | Real HTTP/WS multi-user scenario tests. |

## Key Design Decisions

- Cluster-first: all cross-node shared state must be in Redis or MySQL. Local/in-memory implementations are only for unit tests or local fallback, never production wiring.
- Production `ServerComponentsFactory` requires Redis and database. Do not bypass this to make local startup easier.
- WS and HTTP share `ApiDispatcher`, interceptors, request handlers, and error mapping.
- Message sequence uses Redis INCR per conversation.
- Routing and online status use `RedisRouteTable`; cross-node pushes use `RedisClusterMessageBus`.
- Messages, conversations, users, groups, friends, idempotency, failures, file metadata, and system messages are stored in MySQL.
- Business MQ is selected by `im.mq.type`: Redis Streams or RocketMQ.

## Common Commands

```bash
mvn -pl im-api,im-server -am package -DskipTests
bin/restart-backend.sh
bin/start-cluster.sh
bin/stop-cluster.sh
pnpm --dir im-web dev
pnpm --dir im-scenario-tests scenario:smoke
pnpm --dir im-scenario-tests scenario:cluster-ha
```

## Testing Policy

- Pure logic unit tests may use mocks or in-memory objects.
- Functional/E2E tests involving Redis, MySQL, RocketMQ, MinIO, routing, or cross-node delivery should use real infrastructure.
- Reuse `im-server/src/test/java/com/im/bootstrap/BaseE2ETest.java` for server E2E tests.
- Put multi-user public-protocol flows in `im-scenario-tests/scenarios/`.

## Config Notes

- Server entry loads classpath `application.yml` and optional `application-{im.env}.yml`.
- Root `config/` is a deployment template directory and is not automatically loaded by `Main.loadConfig()`.
- Current code priority is `IM_*` env vars > `-Dim.*` system properties > env YAML > default YAML > properties.
- Local dev usually uses `macbook-dev`.

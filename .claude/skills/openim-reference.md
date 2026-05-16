---
name: openim-reference
description: OpenIM source code reference — architecture, module map, and lookup patterns for cross-referencing during IM system development
---

# OpenIM Source Reference

OpenIM source is located at:

- **Absolute**: `/Users/macbook/java/IdeaProjects/github-source/data-or-view/open-im-server/`
- **Relative**: `../open-im-server/` (from this project root)

## Architecture Overview

OpenIM is a Go-based microservices IM platform with ~8+ processes communicating via gRPC. Service discovery uses etcd or K8s. The main entry points are in `cmd/`:

| Binary | Path | Role |
|--------|------|------|
| `msggateway` | `cmd/msggateway/` | WebSocket gateway — client connections, message push |
| `rpc-msg` | `internal/rpc/msg/` | Message send/receive RPC, seq allocation |
| `msgtransfer` | `internal/msgtransfer/` | Async persistence pipeline (Redis → Kafka → MongoDB) |
| `rpc-user` | `internal/rpc/user/` | User data and online status RPC |
| `push` | `internal/push/` | Message push to online/offline users |

## Core Module Map

| OpenIM Module | Path | Function |
|---------------|------|----------|
| WebSocket Gateway | `internal/msggateway/` | Client connection management, push, kick, multi-login check |
| Auth | `internal/rpc/auth/` | Token generation and validation |
| Conversation | `internal/rpc/conversation/` | Conversation management |
| Friend | `internal/rpc/friend/` | Friendship CRUD |
| Group | `internal/rpc/group/` | Group CRUD, membership |
| Message (RPC) | `internal/rpc/msg/` | Message send/receive, seq |
| Message Transfer | `internal/msgtransfer/` | Kafka consumers, persistence to MongoDB |
| Push | `internal/push/` | Online/offline push |
| User (RPC) | `internal/rpc/user/` | User data, online status |
| Online Cache | `pkg/common/storage/cache/redis/online.go` | Redis-based online status (ZSet + Lua + Pub/Sub) |

## Key Data Models

| Model | Location | Notes |
|-------|----------|-------|
| `MsgDocModel` | `pkg/common/storage/model/msg.go` | MongoDB bucket — 100 messages per doc, seq-based array index |
| `MsgDataModel` | `pkg/common/storage/model/msg.go` | Individual message fields |
| S3/OSS Storage | `pkg/common/storage/s3/` | File/object storage abstraction |

## Lookup Patterns

When researching how OpenIM implements a feature:

1. **Find the RPC interface first** — OpenIM defines gRPC proto interfaces in `pkg/proto/`
2. **Trace to implementation** — RPC handlers are in `internal/rpc/{service}/`
3. **Check persistence** — Storage interfaces in `pkg/common/storage/controller/`, implementations in `database/mgo/` or `cache/redis/`
4. **For message flow**: `internal/rpc/msg/send.go` → publish to Kafka → `internal/msgtransfer/` → MongoDB

## Key Differences from Our Project

- **Language**: Go vs Java (Netty)
- **Architecture**: Microservices vs single-process
- **Persistence**: MongoDB (primary) + Redis (cache/seq) vs MySQL + Redis
- **Message Queue**: Kafka vs Redis Streams / in-memory
- **Service Discovery**: etcd/K8s vs Local/Redis-based discovery
- **Protocol**: Custom binary WS protocol vs unified WS/HTTP via ApiDispatcher

## When to Reference

Use this skill when:
- Designing new features that have OpenIM analogues
- Evaluating persistence strategies
- Considering architecture changes
- Debugging message flow issues
- Designing online status, push, or message sync features

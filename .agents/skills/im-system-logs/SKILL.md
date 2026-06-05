---
name: im-system-logs
description: Use when debugging this IM-system-netty project, checking backend startup/runtime logs, asking where logs are stored, investigating requestId/traceId/user/operation chains, or restarting the backend while preserving or clearing development logs.
---

# IM System Logs

Use this skill only inside the `IM-system-netty` repository.

## Log Files

Primary backend logs live under the repository `logs/` directory:

- `logs/im-system.log`: application logs written by Logback. Use this first for backend runtime errors and request chain logs.
- `logs/backend.log`: stdout/stderr from `bin/restart-backend.sh` background startup. Use this for startup failures before Logback is ready.
- `logs/im-system.YYYYMMDD.N.log`: Logback rolling history.
- `logs/node-1.log` / `logs/node-2.log`: older multi-node local test logs if present.

Current Logback MDC pattern includes:

```text
[trace=...] [req=...] [user=...] [op=...] [conn=...] [seq=...]
```

Prefer searching by `requestId` first:

```bash
grep "req_xxxxx" logs/im-system.log
```

Useful commands:

```bash
tail -f logs/im-system.log
tail -f logs/backend.log
grep "ERROR\|WARN" logs/im-system.log
grep "\[user=332211\]" logs/im-system.log
grep "\[op=chat.send\]" logs/im-system.log
```

## Restart Script Behavior

`bin/restart-backend.sh` is the preferred local backend restart command.

Development default: the script clears these backend logs before restart to keep logs small and readable:

- `logs/backend.log`
- `logs/im-system.log`
- `logs/im-system.*.log`

Keep logs for a failing scenario:

```bash
bin/restart-backend.sh --no-build --keep-logs
```

Force clear logs explicitly:

```bash
bin/restart-backend.sh --no-build --clear-logs
```

## Debugging Workflow

1. If startup failed, inspect `logs/backend.log` first.
2. If a frontend/API operation failed, get `X-Request-Id` from HTTP response headers or `requestId` from WS ACK/frame.
3. Search `logs/im-system.log` by that requestId.
4. Use `user`, `op`, `conn`, and `seq` fields to narrow the chain.
5. If logs are unexpectedly empty, check whether the backend has been restarted and whether `bin/restart-backend.sh` cleared old logs.

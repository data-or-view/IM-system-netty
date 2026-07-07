# Magic Values And Inner Classes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up high-value magic values and inner classes across the IM project without over-abstracting one-off literals.

**Architecture:** Extract only types or constants that cross class/module boundaries or carry project semantics. Keep private test fixtures and one-off local literals in place. Preserve cluster-first behavior and avoid local-memory production state.

**Tech Stack:** Java 21, Maven multi-module backend, React/TypeScript frontend, TypeScript SDK, TypeScript scenario tests.

## Global Constraints

- Do not extract every literal; extract only repeated, easy-to-mistype, or project-semantic values.
- If an inner class is only used inside its enclosing class, keep it nested.
- If an inner class is referenced outside its enclosing class or acts as a reusable contract/dependency carrier, extract it to a top-level type.
- Do not run test suites during implementation; run verification after all edits.
- Respect existing dirty worktree changes; do not revert user or previous-task work.
- Production shared state must remain Redis/MySQL backed; Local implementations are development/test fallback only.

---

### Task 1: Bootstrap Inner Type Extraction

**Files:**
- Create: `im-server/src/main/java/com/im/bootstrap/RuntimeFriendApplyNotifier.java`
- Create: `im-server/src/main/java/com/im/bootstrap/RuntimeGroupApplyNotifier.java`
- Create: `im-server/src/main/java/com/im/bootstrap/RuntimeSystemMessageNotifier.java`
- Create: `im-server/src/main/java/com/im/bootstrap/RuntimeMessageRevokeNotifier.java`
- Create: `im-server/src/main/java/com/im/bootstrap/RuntimeDependencies.java`
- Create: `im-server/src/main/java/com/im/bootstrap/ClusterDependencies.java`
- Create: `im-server/src/main/java/com/im/bootstrap/BusinessDependencies.java`
- Create: `im-server/src/main/java/com/im/bootstrap/StorageDependencies.java`
- Create: `im-server/src/main/java/com/im/bootstrap/CallDependencies.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/DispatcherDependencies.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/StorageComponentsFactory.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/RedisComponentsFactory.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/ConsumerComponentsFactory.java`
- Modify: `im-server/src/test/java/com/im/bootstrap/DispatcherFactoryTest.java`

**Interfaces:**
- Produces top-level package-private bootstrap dependency records and runtime notifier classes in `com.im.bootstrap`.
- Existing factory methods should use `RuntimeDependencies`, `ClusterDependencies`, `BusinessDependencies`, `StorageDependencies`, and `CallDependencies` directly, not as `ServerComponentsFactory.*` nested names.

- [ ] Extract runtime notifier classes from `ServerComponentsFactory` unchanged except package-private top-level visibility.
- [ ] Extract dependency records from `ServerComponentsFactory` to top-level package-private records.
- [ ] Replace external references to `ServerComponentsFactory.*Dependencies` and `ServerComponentsFactory.RuntimeMessageRevokeNotifier`.
- [ ] Remove now-unused imports from `ServerComponentsFactory`.

### Task 2: Bootstrap And Health Semantic Defaults

**Files:**
- Create: `im-server/src/main/java/com/im/bootstrap/BootstrapDefaults.java`
- Create: `im-server/src/main/java/com/im/bootstrap/health/HealthEndpoints.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/Main.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/ServerComponentsFactory.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/TransportServer.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/RedisComponentsFactory.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/StorageComponentsFactory.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/health/HealthProbeHandler.java`
- Modify: `im-server/src/test/java/com/im/bootstrap/http/HttpRequestAdapterTest.java`

**Interfaces:**
- `BootstrapDefaults` carries backend-local defaults such as node id, ports, localhost address, default LiveKit endpoint, default MinIO endpoint, default multi-login strategy, and request drain timeout.
- `HealthEndpoints` carries `/health/live` and `/health/ready` paths.

- [ ] Add constants only for repeated/project-semantic defaults.
- [ ] Update production code to use the constants.
- [ ] Update health tests to use `HealthEndpoints` instead of duplicating path strings.

### Task 3: SDK Runtime Defaults

**Files:**
- Create: `im-sdk/src/config/defaults.ts`
- Modify: `im-sdk/src/index.ts`
- Modify: `im-sdk/src/transport/ws.ts`
- Modify: `im-sdk/src/protocol/request-manager.ts`
- Modify: `im-sdk/src/types.ts`

**Interfaces:**
- `SDK_DEFAULTS` exposes request timeout, connect timeout, heartbeat interval, reconnect max attempts, reconnect backoff base/max, message batch defaults, and seen-message cache size.

- [ ] Replace repeated numeric defaults in SDK runtime code with `SDK_DEFAULTS`.
- [ ] Keep option docs accurate by referencing the same default values in prose.

### Task 4: Scenario Test Defaults

**Files:**
- Create: `im-scenario-tests/src/defaults.ts`
- Modify: `im-scenario-tests/src/config.ts`
- Modify: `im-scenario-tests/scenarios/cluster-ha.ts`
- Modify: `im-scenario-tests/test/config.test.ts`

**Interfaces:**
- `SCENARIO_DEFAULTS` carries local single-node HTTP/WS URLs, password, timeout.
- `CLUSTER_NODE_DEFAULTS` carries node-1/node-2 HTTP/WS URLs.

- [ ] Replace repeated local default URLs and timeout values in scenario source/tests.
- [ ] Keep `package.json` script literals as shell-level defaults because JSON scripts cannot import TS constants.

### Task 5: Verification

**Commands:**
- `git diff --check`
- `JAVA_HOME="/Library/Java/JavaVirtualMachines/graalvm-21.jdk/Contents/Home" PATH="/Library/Java/JavaVirtualMachines/graalvm-21.jdk/Contents/Home/bin:$PATH" "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -B test`
- `PATH="/Users/macbook/.nvm/versions/node/v22.20.0/bin:$PATH" pnpm --dir im-web test:engineering`
- `PATH="/Users/macbook/.nvm/versions/node/v22.20.0/bin:$PATH" pnpm --dir im-web build`
- `PATH="/Users/macbook/.nvm/versions/node/v22.20.0/bin:$PATH" npm --prefix im-sdk test`
- `PATH="/Users/macbook/.nvm/versions/node/v22.20.0/bin:$PATH" pnpm --dir im-scenario-tests scenario:ci`

- [ ] Run all commands after implementation.
- [ ] Inspect final diff for accidental over-extraction or unrelated churn.

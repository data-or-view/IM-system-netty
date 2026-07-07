# P0 Quality Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add enterprise P0 gates for CI, protocol contracts, authorization ownership, health/readiness, and scenario-test layering.

**Architecture:** Keep health/readiness outside `Operation` because probes must work before request admission opens and without authentication. Keep protocol and authorization gates as tests over `Operation`, so new APIs cannot be added without explicit contract and authz classification. Keep scenario layering in `im-scenario-tests/package.json` so local and CI commands share one source of truth.

**Tech Stack:** Java 21, JUnit 5, Netty embedded channel tests, Maven, TypeScript/Node test runner, pnpm, GitHub Actions YAML.

## Global Constraints

- Cluster-first: no production shared state may move into local memory.
- No new package managers or system dependencies.
- Health/readiness must not require authentication.
- Contract and authz gates must fail when a new `Operation` lacks classification.
- Scenario layers must reuse existing real HTTP/WS scenario scripts.

---

### Task 1: Health And Readiness

**Files:**
- Create: `im-server/src/main/java/com/im/bootstrap/health/HealthStatus.java`
- Create: `im-server/src/main/java/com/im/bootstrap/health/HealthSnapshot.java`
- Create: `im-server/src/main/java/com/im/bootstrap/health/HealthProbeHandler.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/http/HttpRequestAdapter.java`
- Modify: `im-server/src/main/java/com/im/bootstrap/TransportServer.java`
- Test: `im-server/src/test/java/com/im/bootstrap/http/HttpRequestAdapterTest.java`

**Interfaces:**
- Produces: `HealthProbeHandler.handleIfHealthProbe(ChannelHandlerContext, FullHttpRequest): boolean`
- Consumes: `RequestAdmission.isOpen()`

- [x] Add failing embedded-channel tests for `/health/live` and `/health/ready`.
- [x] Implement health snapshot and HTTP probe handler.
- [x] Wire probe handler before business operation lookup.
- [x] Verify `mvn -pl im-server -Dtest=HttpRequestAdapterTest test`.

### Task 2: Protocol Contract Gate

**Files:**
- Create: `im-api/src/main/java/com/im/api/OperationContract.java`
- Create: `im-api/src/test/java/com/im/api/OperationContractTest.java`
- Modify: `im-api/README.md`

**Interfaces:**
- Produces: `OperationContract.forOperation(Operation): OperationContract`

- [x] Add failing tests requiring every operation to have a contract category and stable transport mapping.
- [x] Implement the operation contract registry.
- [x] Verify `mvn -pl im-api test`.

### Task 3: Authorization Matrix Gate

**Files:**
- Create: `im-api/src/main/java/com/im/api/AuthzPolicy.java`
- Create: `im-api/src/test/java/com/im/api/AuthzPolicyTest.java`
- Add: `docs/authz-matrix.md`

**Interfaces:**
- Produces: `AuthzPolicy.forOperation(Operation): AuthzPolicy`

- [x] Add failing tests requiring every operation to have an authz policy.
- [x] Implement policy categories for public, self, conversation, friend, group, file, system, admin, and ws-session operations.
- [x] Document the matrix in `docs/authz-matrix.md`.
- [x] Verify `mvn -pl im-api test`.

### Task 4: Scenario Layering

**Files:**
- Modify: `im-scenario-tests/package.json`
- Modify: `im-scenario-tests/README.md`
- Test: `im-scenario-tests/test/config.test.ts`

**Interfaces:**
- Produces scripts: `scenario:core`, `scenario:p0`, `scenario:ci`, `scenario:full`, `scenario:chaos`

- [x] Add failing package-script test for required layers.
- [x] Add package scripts reusing existing scenarios.
- [x] Document layer intent and when to run each.
- [x] Verify `pnpm --dir im-scenario-tests test`.

### Task 5: CI Quality Gate

**Files:**
- Create: `.github/workflows/p0-quality-gate.yml`
- Modify: `docs/ai-project-guide.md`

**Interfaces:**
- Produces GitHub Actions jobs: backend, frontend, sdk, scenario-static, scenario-smoke-docs.

- [x] Add CI workflow with Java, frontend, SDK, and scenario test jobs.
- [x] Keep live-cluster scenarios explicit/manual because they require Redis/MySQL/MQ/MinIO services.
- [x] Document local equivalent commands.
- [x] Verify YAML and scripts by local commands.

### Task 6: Final Verification

**Files:** none

- [ ] Run Maven tests for touched modules.
- [ ] Run `pnpm --dir im-web test:engineering`.
- [ ] Run `pnpm --dir im-web build`.
- [ ] Run `npm --prefix im-sdk test`.
- [ ] Run `pnpm --dir im-scenario-tests test`.
- [ ] Run feasible scenario layers against local backend/cluster if services are available.

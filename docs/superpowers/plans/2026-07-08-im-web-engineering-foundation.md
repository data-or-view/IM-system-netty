# im-web Engineering Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add maintainable frontend engineering foundations for global errors, typed app events, namespaced logging, route guards, and boundary reporting in `im-web`.

**Architecture:** Keep the feature small and local to `im-web`: add focused library modules under `src/lib` and move route guard decisions into a pure helper under `src/config`. Wire React components to those helpers without changing product UI flow. Use existing `node:test` engineering checks plus TypeScript build for verification.

**Tech Stack:** React 18, React Router 7, Vite, TypeScript, `node:test`, existing `sonner` toast.

## Global Constraints

- Do not touch non-`im-web` runtime code in this task.
- Do not introduce a heavy observability dependency; the first enterprise step is a clean abstraction boundary.
- Keep route auth behavior compatible with current login, redirect, and auth-check flow.
- Use TDD: add failing engineering tests before production code.

---

### Task 1: Engineering Contract Tests

**Files:**
- Modify: `im-web/scripts/engineering-tests.mjs`

**Interfaces:**
- Consumes: current source files as text and small TS modules loaded through `loadTsModule`.
- Produces: failing checks for `app-events`, `logger`, `route-guards`, and boundary/error integration.

- [ ] **Step 1: Add tests for typed events, logger abstraction, and route guard helpers.**
- [ ] **Step 2: Run `PATH="/Users/macbook/.nvm/versions/node/v22.20.0/bin:$PATH" pnpm --dir im-web test:engineering` and confirm the new tests fail because files/functions are missing.**

### Task 2: Core Engineering Libraries

**Files:**
- Create: `im-web/src/lib/logger.ts`
- Create: `im-web/src/lib/app-events.ts`
- Modify: `im-web/src/lib/app-errors.ts`
- Modify: `im-web/src/config/app-behavior.ts`

**Interfaces:**
- `createLogger(namespace: string): AppLogger`
- `emitAppEvent(type, detail)`, `listenAppEvent(type, handler)`
- `notifyAppError(error, fallback, source)` continues to work, now through typed events and logger.

- [ ] **Step 1: Implement logger levels, namespace normalization, console sink, and runtime level threshold.**
- [ ] **Step 2: Implement typed DOM event helpers for app errors and existing sidebar request refresh events.**
- [ ] **Step 3: Route `notifyAppError` through typed event helpers and log emitted errors.**
- [ ] **Step 4: Run engineering tests and fix only failures in this scope.**

### Task 3: Route Guard and Boundary Wiring

**Files:**
- Create: `im-web/src/config/route-guards.ts`
- Modify: `im-web/src/App.tsx`
- Modify: `im-web/src/components/GlobalErrorHandler.tsx`
- Modify: `im-web/src/components/RouteErrorBoundary.tsx`
- Modify: `im-web/src/store/useStoreSdkEvents.ts`
- Modify: sidebar dialogs that listen for custom events if needed.

**Interfaces:**
- `resolveAuthRoute(input): AuthRouteDecision`
- `GlobalErrorHandler` listens through `listenAppEvent`.
- `RouteErrorBoundary` logs render failures and emits app error notices.

- [ ] **Step 1: Extract pure route guard decisions from `AuthGate`.**
- [ ] **Step 2: Wire `App.tsx` to `resolveAuthRoute` while preserving current redirects.**
- [ ] **Step 3: Replace raw `window.dispatchEvent/addEventListener` for app-owned events with `app-events`.**
- [ ] **Step 4: Replace direct `console.error/warn` in touched files with namespaced loggers.**

### Task 4: Verification

**Files:**
- No production edits.

- [ ] **Step 1: Run `pnpm --dir im-web test:engineering`.**
- [ ] **Step 2: Run `pnpm --dir im-web build`.**
- [ ] **Step 3: Run `git diff --check`.**
- [ ] **Step 4: Review `git status --short -- im-web` and summarize changed files.**

## Self-Review

- Spec coverage: global error handling, event management, error handling, boundaries, logging, and route guard are each mapped to tasks.
- Placeholder scan: no implementation placeholders; details are intentionally scoped to the existing app shape.
- Type consistency: task interfaces use stable names that later code will import directly.

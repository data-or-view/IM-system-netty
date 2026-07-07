import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { createRequire } from "node:module";
import vm from "node:vm";
import ts from "typescript";

const root = path.resolve(import.meta.dirname, "..");
const require = createRequire(import.meta.url);

function readSource(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), "utf8");
}

function lineCount(relativePath) {
  return readSource(relativePath).split(/\r?\n/).length;
}

function loadTsModule(relativePath) {
  const filename = path.join(root, relativePath);
  const source = fs.readFileSync(filename, "utf8");
  const compiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
    },
    fileName: filename,
  }).outputText;

  const module = { exports: {} };
  const context = {
    module,
    exports: module.exports,
    require,
    URLSearchParams,
  };
  vm.runInNewContext(compiled, context, { filename });
  return module.exports;
}

test("route helpers centralize app paths and encode route params", () => {
  const { APP_ROUTES, getRedirectTarget } = loadTsModule("src/config/routes.ts");

  assert.equal(APP_ROUTES.login, "/login");
  assert.equal(APP_ROUTES.chat, "/chat");
  assert.equal(APP_ROUTES.createGroup, "/chat/create-group");
  assert.equal(APP_ROUTES.user("u/1"), "/chat/user/u%2F1");
  assert.equal(APP_ROUTES.group("g 1"), "/chat/group/g%201");
  assert.equal(APP_ROUTES.loginWithRedirect("/chat/group/g 1"), "/login?redirect=%2Fchat%2Fgroup%2Fg%201");

  assert.equal(getRedirectTarget("?redirect=/chat/user/u1"), "/chat/user/u1");
  assert.equal(getRedirectTarget("?redirect=//evil.example"), "/chat");
  assert.equal(getRedirectTarget("?redirect=https://evil.example"), "/chat");
  assert.equal(getRedirectTarget(""), "/chat");
});

test("auth guard only logs out for real authentication failures", () => {
  const { isAuthExpiredError, authCheckFailureMessage } = loadTsModule("src/lib/app-errors.ts");

  assert.equal(isAuthExpiredError({ code: 401, message: "unauthorized" }), true);
  assert.equal(isAuthExpiredError({ kind: "auth", code: -1, message: "invalid token" }), true);
  assert.equal(isAuthExpiredError({ code: 403, message: "forbidden" }), false);
  assert.equal(isAuthExpiredError({ kind: "connection", code: -1, message: "Not connected" }), false);
  assert.equal(isAuthExpiredError({ kind: "timeout", code: -1, message: "timeout" }), false);
  assert.equal(authCheckFailureMessage({ kind: "timeout", message: "timeout" }), "服务响应超时，请稍后重试");
});

test("behavior constants collect non-visual magic values", () => {
  const { APP_BEHAVIOR } = loadTsModule("src/config/app-behavior.ts");

  assert.equal(APP_BEHAVIOR.cache.userProfileTtlMs, 5 * 60 * 1000);
  assert.equal(APP_BEHAVIOR.cache.groupInfoTtlMs, 5 * 60 * 1000);
  assert.equal(APP_BEHAVIOR.cache.groupMembersTtlMs, 60 * 1000);
  assert.equal(APP_BEHAVIOR.refresh.debounceMs, 80);
  assert.equal(APP_BEHAVIOR.search.defaultLimit, 20);
  assert.equal(APP_BEHAVIOR.messages.historyPageSize, 20);
  assert.equal(APP_BEHAVIOR.systemMessages.listLimit, 30);
});

test("app auth guard uses shared routes and only logs out for expired credentials", () => {
  const source = readSource("src/App.tsx");

  assert.match(source, /APP_ROUTES/);
  assert.match(source, /getRedirectTarget/);
  assert.match(source, /isAuthExpiredError/);
  assert.match(source, /authCheckFailureMessage/);
  assert.doesNotMatch(source, /function getRedirectTarget/);
  assert.doesNotMatch(source, /location\.pathname === ["']\/login["']/);
});

test("route error boundary navigates through React Router instead of browser history", () => {
  const source = readSource("src/components/RouteErrorBoundary.tsx");

  assert.match(source, /onNavigateHome/);
  assert.doesNotMatch(source, /window\.history/);
  assert.doesNotMatch(source, /PopStateEvent/);
});

test("runtime behavior uses shared constants instead of scattered numbers", () => {
  const store = readSource("src/store/store.tsx");
  const chatArea = readSource("src/components/ChatArea.tsx");
  const conversationHistory = readSource("src/components/chat/useConversationHistory.ts");

  assert.match(store, /APP_BEHAVIOR/);
  assert.doesNotMatch(store, /const USER_PROFILE_TTL_MS/);
  assert.doesNotMatch(store, /const GROUP_INFO_TTL_MS/);
  assert.doesNotMatch(store, /const GROUP_MEMBERS_TTL_MS/);
  assert.doesNotMatch(store, /limit:\s*30/);
  assert.doesNotMatch(store, /setTimeout\(flushRefreshTasks,\s*80\)/);
  assert.doesNotMatch(store, /limit = 20/);
  assert.match(`${chatArea}\n${conversationHistory}`, /APP_BEHAVIOR\.messages\.historyPageSize/);
  assert.doesNotMatch(chatArea, /maxSeq - 20/);
  assert.doesNotMatch(conversationHistory, /maxSeq - 20/);
});

test("feature routes use central route helpers outside the route config", () => {
  const files = [
    "src/App.tsx",
    "src/components/ChatArea.tsx",
    "src/components/Sidebar.tsx",
    "src/pages/CreateGroupPage.tsx",
    "src/pages/GroupInfoPage.tsx",
    "src/pages/UserProfilePage.tsx",
  ];

  for (const file of files) {
    const source = readSource(file);
    assert.doesNotMatch(source, /navigate\((["'`])\/chat/);
    assert.doesNotMatch(source, /<Navigate\s+to=(["'`])\/chat/);
    assert.doesNotMatch(source, /location\.pathname === (["'`])\/login/);
  }
});

test("unused SDK example entry points are not shipped as app source", () => {
  assert.equal(fs.existsSync(path.join(root, "src/sdk/useIM.ts")), false);
  assert.equal(fs.existsSync(path.join(root, "src/sdk/ExampleUsage.tsx")), false);
});

test("ChatArea stays an orchestration component with chat responsibilities split out", () => {
  assert.ok(lineCount("src/components/ChatArea.tsx") <= 360);
  for (const file of [
    "src/components/chat/ChatHeader.tsx",
    "src/components/chat/GroupCallBanner.tsx",
    "src/components/chat/MessageList.tsx",
    "src/components/chat/MessageComposer.tsx",
    "src/components/chat/useConversationHistory.ts",
    "src/components/chat/useActiveGroupCall.ts",
  ]) {
    assert.equal(fs.existsSync(path.join(root, file)), true, `${file} should exist`);
  }
});

test("large frontend modules are split by responsibility", () => {
  for (const [file, maxLines] of [
    ["src/store/store.tsx", 520],
    ["src/components/Sidebar.tsx", 300],
    ["src/components/call/CallProvider.tsx", 420],
    ["src/pages/GroupInfoPage.tsx", 360],
  ]) {
    assert.ok(lineCount(file) <= maxLines, `${file} should stay below ${maxLines} lines`);
  }

  for (const file of [
    "src/store/store-types.ts",
    "src/store/store-reducer.ts",
    "src/store/store-helpers.ts",
    "src/components/sidebar/SidebarRail.tsx",
    "src/components/sidebar/SidebarLists.tsx",
    "src/components/sidebar/SidebarItems.tsx",
    "src/components/call/call-types.ts",
    "src/components/call/call-config.ts",
    "src/components/call/call-attention.ts",
    "src/components/call/call-errors.ts",
    "src/pages/group-info/group-info-utils.ts",
    "src/pages/group-info/useGroupManagement.ts",
    "src/pages/group-info/GroupMemberList.tsx",
    "src/pages/group-info/GroupEditDialogs.tsx",
  ]) {
    assert.equal(fs.existsSync(path.join(root, file)), true, `${file} should exist`);
  }
});

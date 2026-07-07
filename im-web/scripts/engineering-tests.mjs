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

function loadTsModule(relativePath, options = {}) {
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
    require: (specifier) => options.stubs?.[specifier] ?? require(specifier),
    URL,
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
  const { isAuthExpiredError, authCheckFailureMessage, toAppErrorNotice } = loadTsModule("src/lib/app-errors.ts");

  assert.equal(isAuthExpiredError({ code: 401, message: "unauthorized" }), true);
  assert.equal(isAuthExpiredError({ kind: "auth", code: -1, message: "invalid token" }), true);
  assert.equal(isAuthExpiredError({ code: 403, message: "forbidden" }), false);
  assert.equal(isAuthExpiredError({ kind: "connection", code: -1, message: "Not connected" }), false);
  assert.equal(isAuthExpiredError({ kind: "timeout", code: -1, message: "timeout" }), false);
  assert.equal(authCheckFailureMessage({ kind: "timeout", message: "timeout" }), "服务响应超时，已保留登录状态");
  assert.equal(
    toAppErrorNotice({ kind: "connection", message: "Failed to fetch" }, "暂时无法连接到后端，已保留登录状态", "auth-check").message,
    "暂时无法连接到后端，已保留登录状态",
  );
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

test("message media URLs reject executable and local schemes", () => {
  const { safeExternalUrl, safeMediaUrl } = loadTsModule("src/lib/safe-url.ts");

  assert.equal(safeExternalUrl("https://cdn.example.test/a.png"), "https://cdn.example.test/a.png");
  assert.equal(safeExternalUrl("/api/file/download?id=1"), "/api/file/download?id=1");
  assert.equal(safeExternalUrl(" javascript:alert(1) "), undefined);
  assert.equal(safeExternalUrl("data:text/html,<script>alert(1)</script>"), undefined);
  assert.equal(safeExternalUrl("file:///etc/passwd"), undefined);
  assert.equal(safeMediaUrl("blob:http://localhost/object-1"), "blob:http://localhost/object-1");
});

test("message revoke menu is only exposed for persisted successful messages", () => {
  const source = readSource("src/components/chat/MessageList.tsx");

  assert.match(source, /const canRevoke = .*message\.seq > 0/);
  assert.match(source, /canRevoke &&/);
  assert.match(source, /message\.status !== -1/);
  assert.match(source, /aria-label="更多消息操作"/);
});

test("sidebar icon buttons expose accessible names", () => {
  const rail = readSource("src/components/sidebar/SidebarRail.tsx");
  const sidebar = readSource("src/components/Sidebar.tsx");
  const lists = readSource("src/components/sidebar/SidebarLists.tsx");
  const friendRequests = readSource("src/components/sidebar/FriendRequestDialog.tsx");
  const groupRequests = readSource("src/components/sidebar/GroupRequestDialog.tsx");

  assert.match(rail, /aria-label=\{label\}/);
  assert.match(lists, /aria-label="好友申请"/);
  assert.match(lists, /aria-label="群申请"/);
  assert.match(sidebar, /<MobileTabIcon label="消息"/);
  assert.match(sidebar, /<MobileTabIcon label="联系人"/);
  assert.match(sidebar, /<MobileTabIcon label="群组"/);
  assert.match(friendRequests, /aria-label=\{`拒绝 \$\{a\.fromUserId\} 的好友申请`\}/);
  assert.match(friendRequests, /aria-label=\{`同意 \$\{a\.fromUserId\} 的好友申请`\}/);
  assert.match(groupRequests, /aria-label=\{`拒绝 \$\{apply\.userId\} 加入/);
  assert.match(groupRequests, /aria-label=\{`同意 \$\{apply\.userId\} 加入/);
});

test("triggered dialogs and icon-only controls expose accessible names", () => {
  const appPage = readSource("src/components/AppPage.tsx");
  const sidebar = readSource("src/components/Sidebar.tsx");
  const callDialog = readSource("src/components/call/CallDialog.tsx");
  const composer = readSource("src/components/chat/MessageComposer.tsx");
  const sidebarItems = readSource("src/components/sidebar/SidebarItems.tsx");
  const groupMembers = readSource("src/pages/group-info/GroupMemberList.tsx");
  const profile = readSource("src/pages/UserProfilePage.tsx");
  const chatHeader = readSource("src/components/chat/ChatHeader.tsx");
  const dialogParts = readSource("src/components/sidebar/DialogParts.tsx");
  const userSearch = readSource("src/components/sidebar/UserSearchDialog.tsx");
  const login = readSource("src/pages/LoginPage.tsx");

  assert.match(appPage, /aria-label="返回"/);
  assert.match(sidebar, /aria-label=\{`个人资料：\$\{currentDisplayName\}`\}/);
  assert.match(chatHeader, /aria-label="语音通话"/);
  assert.match(chatHeader, /aria-label="视频通话"/);
  assert.match(chatHeader, /aria-label=\{activeGroupCall \? "加入群视频" : "发起群视频"\}/);
  assert.match(chatHeader, /aria-label="查看资料"/);
  assert.match(callDialog, /aria-label=\{label\}/);
  assert.match(callDialog, /title=\{label\}/);
  assert.match(composer, /aria-label=\{uploading \? "正在上传文件" : "发送文件"\}/);
  assert.match(composer, /aria-label="发送消息"/);
  assert.match(dialogParts, /aria-label="搜索"/);
  assert.match(userSearch, /aria-label=\{`向 \$\{user\.nickname \|\| user\.userId\} 发送好友申请`\}/);
  assert.match(sidebarItems, /aria-label=\{`打开与 \$\{displayName\} 的聊天，用户 ID：\$\{friend\.friendUserId\}`\}/);
  assert.match(sidebarItems, /aria-label=\{`更多好友操作：\$\{displayName\}`\}/);
  assert.match(sidebarItems, /md:group-focus-within:visible/);
  assert.match(sidebarItems, /md:invisible/);
  assert.match(groupMembers, /aria-label=\{`查看成员资料：\$\{memberName\}，用户 ID：\$\{member\.userId\}`\}/);
  assert.match(groupMembers, /aria-label=\{`\$\{member\.roleLevel === GroupMemberRole\.ADMIN \? "取消管理员" : "设为管理员"\}：\$\{memberName\}`\}/);
  assert.match(groupMembers, /aria-label=\{`转让群主给：\$\{memberName\}`\}/);
  assert.match(groupMembers, /aria-label=\{`移出群聊：\$\{memberName\}`\}/);
  assert.match(profile, /aria-label="更换头像"/);
  assert.match(profile, /applyingFriend/);
  assert.match(login, /htmlFor="login-user-id"/);
  assert.match(login, /id="login-user-id"/);
  assert.match(login, /aria-pressed=\{isLogin\}/);
});

test("page loads can request strict store errors instead of silent refresh semantics", () => {
  const storeTypes = readSource("src/store/store-types.ts");
  const store = readSource("src/store/store.tsx");
  const groupInfo = readSource("src/pages/GroupInfoPage.tsx");
  const profile = readSource("src/pages/UserProfilePage.tsx");

  assert.match(storeTypes, /export interface FetchOptions/);
  assert.match(storeTypes, /silent\?: boolean/);
  assert.match(store, /if \(options\?\.silent === false\) throw err;/);
  assert.match(groupInfo, /fetchGroupInfo\(groupId, \{ silent: false \}\)/);
  assert.match(groupInfo, /fetchGroupMembers\(groupId, \{ silent: false \}\)/);
  assert.match(profile, /fetchFriends\(\{ silent: false \}\)/);
  assert.match(profile, /fetchUserProfile\(userId, \{ silent: false \}\)/);
});

test("SDK token clear events remove persisted auth instead of preserving stale tokens", () => {
  const helpers = readSource("src/store/store-helpers.ts");
  const sdkEvents = readSource("src/store/useStoreSdkEvents.ts");
  const store = readSource("src/store/store.tsx");
  const storageKeys = readSource("src/config/storage-keys.ts");

  assert.match(helpers, /if \(!tokens\.token && !tokens\.refreshToken\)/);
  assert.match(helpers, /localStorage\.removeItem\(AUTH_TOKEN_KEY\)/);
  assert.match(helpers, /localStorage\.removeItem\(AUTH_REFRESH_TOKEN_KEY\)/);
  assert.match(sdkEvents, /token: tokens\.token \?\? null/);
  assert.match(sdkEvents, /refreshToken: tokens\.refreshToken \?\? null/);
  assert.match(storageKeys, /AUTH_LOGOUT_EVENT_KEY/);
  assert.match(store, /window\.addEventListener\("storage", onAuthStorage\)/);
  assert.match(store, /event\.key !== AUTH_LOGOUT_EVENT_KEY/);
  assert.match(store, /im\.disconnect\(\);\n\s+clearStoredAuth\(state\.userId\);/);
});

test("create group controls expose binary and selection state", () => {
  const source = readSource("src/pages/CreateGroupPage.tsx");

  assert.match(source, /role="switch"/);
  assert.match(source, /aria-checked=\{needVerification\}/);
  assert.match(source, /aria-pressed=\{selected\}/);
  assert.match(source, /aria-label=\{`\$\{selected \? "取消选择" : "选择"\}初始成员/);
});

test("call failures clean up server-side call state", () => {
  const callProvider = readSource("src/components/call/CallProvider.tsx");
  const callTypes = readSource("src/components/call/call-types.ts");

  assert.match(callTypes, /suppressFailureToast\?: boolean/);
  assert.match(callProvider, /await im\.group\.leaveCall\(group\.groupId\)\.catch/);
  assert.match(callProvider, /await im\.group\.endCall\(group\.groupId\)\.catch/);
  assert.match(callProvider, /let acceptedSent = false/);
  assert.match(callProvider, /SignalingAction\.HANGUP, current\.roomId, undefined, "media_failed"/);
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

test("conversation refresh preserves the active optimistic chat while persistence catches up", () => {
  const domain = readSource("src/store/domain.ts");

  assert.match(domain, /const activeConversation = state\.activeConversationId/);
  assert.match(domain, /state\.conversations\.find\(\(conversation\) => conversation\.conversationId === state\.activeConversationId\)/);
  assert.match(domain, /!normalized\.some\(\(conversation\) => conversation\.conversationId === activeConversation\.conversationId\)/);
  assert.match(domain, /sortConversations\(\[\.\.\.normalized, activeConversation\]\)/);
});

test("message merge collapses a local pending message into its server ack by client message id", () => {
  const { mergeConversationMessages } = loadTsModule("src/store/domain.ts", {
    stubs: {
      "im-sdk": {
        ApplyHandleResult: { AGREED: "AGREED", REJECTED: "REJECTED" },
        ConversationType: { SINGLE: "SINGLE", GROUP: "GROUP" },
        MessageReceiveOption: { NORMAL: "NORMAL" },
      },
      "@/lib/messages": { toViewMessage: (message) => message },
    },
  });

  const pending = {
    messageId: "client_msg_1",
    seq: 0,
    senderUserId: "alice",
    conversationId: "single_alice_bob",
    contentType: 101,
    content: "{\"text\":\"hello\"}",
    createTime: 1000,
    status: 0,
  };
  const ack = {
    ...pending,
    seq: 7,
    createTime: 1001,
    status: 1,
  };

  const merged = mergeConversationMessages([], [pending, ack]);

  assert.equal(merged.length, 1);
  assert.equal(merged[0].messageId, "client_msg_1");
  assert.equal(merged[0].seq, 7);
  assert.equal(merged[0].status, 1);
});

test("sent message migration rewrites pending messages to the acknowledged conversation id", () => {
  const reducer = readSource("src/store/store-reducer.ts");

  assert.match(reducer, /\(state\.messages\[previousId\] \|\| \[\]\)\.map/);
  assert.match(reducer, /\{\s*\.\.\.message,\s*conversationId: nextId\s*\}/);
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

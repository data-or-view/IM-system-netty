import test from "node:test";
import assert from "node:assert/strict";

import { MessageAPI } from "../dist/api/message.js";
import { UserAPI } from "../dist/api/user.js";
import { FriendAPI } from "../dist/api/friend.js";
import { GroupAPI } from "../dist/api/group.js";
import { SystemAPI } from "../dist/api/system.js";
import { createIM } from "../dist/index.js";
import { HttpTransport } from "../dist/transport/http.js";
import { WsTransport } from "../dist/transport/ws.js";
import {
  createClientMsgId,
  IMConfigError,
  IMConnectionError,
  IMError,
  IMProtocolError,
  IMTimeoutError,
  MessageContentType,
  normalizeSignalingContent,
  parseMessageContent,
} from "../dist/index.js";

function createCapturingTransport(responseData) {
  const sentFrames = [];
  const transport = {
    request(op, params = {}) {
      const { frame, promise } = this.requestManager.createRequest(op, params);
      this.send(frame);
      return promise;
    },
    requestManager: {
      createRequest(op, params = {}) {
        const frame = { op, seq: sentFrames.length + 1, ...params };
        return {
          frame,
          promise: Promise.resolve({
            op: `${op}_ack`,
            seq: frame.seq,
            code: 0,
            data: typeof responseData === "function" ? responseData(op, params) : responseData,
          }),
        };
      },
    },
    send(frame) {
      sentFrames.push(frame);
    },
  };
  return { transport, sentFrames };
}

test("message.pull unwraps backend message envelope", async () => {
  const message = { messageId: "m1", conversationId: "c1", sequenceId: 1 };
  const { transport } = createCapturingTransport({
    conversationId: "c1",
    messages: [message],
    count: 1,
    maxSeq: 8,
  });

  const api = new MessageAPI(transport, {
    post: async () => ({
      conversationId: "c1",
      messages: [message],
      count: 1,
      maxSeq: 8,
    }),
    get: async () => ({}),
  });

  assert.deepEqual(await api.pull("c1", 1), [message]);
});

test("message.seq unwraps backend maxSeq envelope", async () => {
  const { transport } = createCapturingTransport({
    conversationId: "c1",
    maxSeq: 42,
  });

  const api = new MessageAPI(transport, {
    get: async () => ({ conversationId: "c1", maxSeq: 42 }),
    post: async () => ({}),
  });

  assert.equal(await api.seq("c1"), 42);
});

test("message.sync sends backend seqs map and unwraps sync result", async () => {
  const calls = [];
  const { transport } = createCapturingTransport({});

  const api = new MessageAPI(transport, {
    post: async (_path, body) => {
      calls.push(body);
      return { syncs: [{ conversationId: "c1", messages: [{ messageId: "m2" }], maxSeq: 9 }] };
    },
    get: async () => ({}),
  });

  assert.deepEqual(await api.sync("c1", 3), [{ conversationId: "c1", messages: [{ messageId: "m2" }], maxSeq: 9 }]);
  assert.deepEqual(calls[0].seqs, { c1: 3 });
  assert.equal(calls[0].conversationId, undefined);
  assert.equal(calls[0].lastSeq, undefined);
});

test("message.search maps SDK paging fields to backend limit/offset and totalCount response", async () => {
  const calls = [];
  const { transport } = createCapturingTransport({});

  const api = new MessageAPI(transport, {
    post: async (_path, body) => {
      calls.push(body);
      return {
      messages: [{ messageId: "m3" }],
      totalCount: 12,
      hasMore: true,
      };
    },
    get: async () => ({}),
  });
  const result = await api.search({
    conversationId: "c1",
    keyword: "hello",
    pageSize: 5,
    page: 3,
  });

  assert.deepEqual(result, {
    messages: [{ messageId: "m3" }],
    total: 12,
    hasMore: true,
  });
  assert.deepEqual(calls[0].conversationIds, ["c1"]);
  assert.equal(calls[0].limit, 5);
  assert.equal(calls[0].offset, 10);
  assert.equal(calls[0].pageSize, undefined);
  assert.equal(calls[0].page, undefined);
});

test("group.create leaves groupId generation to backend and sends members array", async () => {
  const calls = [];
  const api = new GroupAPI({
    get: async () => ({}),
    post: async (_path, body) => {
      calls.push(body);
      return { groupId: "grp_server_generated", status: "OK" };
    },
  });

  const result = await api.create("测试群", 0, ["u2", "u3"]);

  assert.equal(result.groupId, "grp_server_generated");
  assert.equal(calls[0].groupId, undefined);
  assert.equal(calls[0].groupName, "测试群");
  assert.deepEqual(calls[0].members, ["u2", "u3"]);
});

test("group.list unwraps backend groups envelope", async () => {
  const groups = [{ groupId: "grp_1", groupName: "研发群" }];
  const api = new GroupAPI({
    get: async () => ({ groups, count: 1 }),
    post: async () => ({}),
  });

  assert.deepEqual(await api.list(), groups);
});


function createHttpCapture(responseData) {
  const calls = [];
  const http = {
    get(path, query) {
      calls.push({ method: "GET", path, query });
      return Promise.resolve(typeof responseData === "function" ? responseData("GET", path, query) : responseData);
    },
    post(path, body) {
      calls.push({ method: "POST", path, body });
      return Promise.resolve(typeof responseData === "function" ? responseData("POST", path, body) : responseData);
    },
  };
  return { http, calls };
}

test("resource APIs use HTTP while realtime message send stays on websocket and returns send ack", async () => {
  const sendAck = { messageId: "client-generated-1", status: "RECEIVED", conversationId: "single_u1_u2", seq: 7 };
  const { transport: ws, sentFrames } = createCapturingTransport(sendAck);
  const { http, calls } = createHttpCapture((method, path, payload) => {
    if (path === "/api/group/list") return { groups: [{ groupId: "g1", groupName: "研发群" }] };
    if (path === "/api/msg/pull") return { messages: [{ messageId: "m2" }] };
    return { status: "OK", payload };
  });

  const groupApi = new GroupAPI(http);
  const messageApi = new MessageAPI(ws, http);

  assert.deepEqual(await groupApi.list(), [{ groupId: "g1", groupName: "研发群" }]);
  assert.deepEqual(await messageApi.pull("c1", 1), [{ messageId: "m2" }]);
  const ack = await messageApi.send({ toUserId: "u2", contentType: "text", content: { text: "hi" } });

  assert.deepEqual(ack, sendAck);
  assert.deepEqual(calls.map((c) => `${c.method} ${c.path}`), ["GET /api/group/list", "POST /api/msg/pull"]);
  assert.deepEqual(calls[1].body, { conversationId: "c1", startSeq: 1 });
  assert.equal(sentFrames[0].op, "chat.send");
  assert.equal(sentFrames[0]._ct, "text");
  assert.deepEqual(sentFrames[0].content, { text: "hi" });
  assert.match(sentFrames[0].clientMsgId, /^c_[0-9a-z]+_[0-9a-z]{8}$/);
  assert.equal(sentFrames[0].contentType, undefined);
});

test("message.send preserves caller supplied clientMsgId", async () => {
  const sendAck = { messageId: "client-fixed-1", status: "RECEIVED", conversationId: "single_u1_u2", seq: 7 };
  const { transport: ws, sentFrames } = createCapturingTransport(sendAck);
  const messageApi = new MessageAPI(ws);

  await messageApi.send({
    toUserId: "u2",
    contentType: "text",
    content: { text: "hi" },
    clientMsgId: "client-fixed-1",
  });

  assert.equal(sentFrames[0].clientMsgId, "client-fixed-1");
});

test("message.sendGroup sends a clientMsgId", async () => {
  const sendAck = { messageId: "group-fixed-1", status: "RECEIVED", conversationId: "group_g1", seq: 7 };
  const { transport: ws, sentFrames } = createCapturingTransport(sendAck);
  const messageApi = new MessageAPI(ws);

  await messageApi.sendGroup({
    groupId: "g1",
    contentType: "text",
    content: { text: "hi" },
    clientMsgId: "group-fixed-1",
  });

  assert.equal(sentFrames[0].op, "chat.send.group");
  assert.equal(sentFrames[0].clientMsgId, "group-fixed-1");
});

test("message.send rejects malformed send ack at SDK boundary", async () => {
  const { transport: ws } = createCapturingTransport({ status: "RECEIVED", conversationId: "c1" });
  const messageApi = new MessageAPI(ws);

  await assert.rejects(
    () => messageApi.send({ toUserId: "u2", contentType: "text", content: { text: "hi" } }),
    (err) => err instanceof IMProtocolError && err.message.includes("Invalid send message ack"),
  );
});

test("createClientMsgId is exported from the SDK public entry", () => {
  assert.match(createClientMsgId(), /^c_[0-9a-z]+_[0-9a-z]{8}$/);
});

test("message.startCall sends invite signaling over websocket", async () => {
  const calling = {
    status: "CALLING",
    roomId: "room_1",
    token: "caller-token",
    sfuEndpoint: "ws://localhost:7880",
  };
  const { transport: ws, sentFrames } = createCapturingTransport(calling);
  const messageApi = new MessageAPI(ws);

  const result = await messageApi.startCall({ toUserId: "u2", callType: "video" });

  assert.deepEqual(result, calling);
  assert.equal(sentFrames[0].op, "chat.send");
  assert.equal(sentFrames[0].toUserId, "u2");
  assert.equal(sentFrames[0]._ct, "signal");
  assert.match(sentFrames[0].clientMsgId, /^c_[0-9a-z]+_[0-9a-z]{8}$/);
  assert.deepEqual(sentFrames[0].content, {
    action: "INVITE",
    callType: "video",
  });
});

test("normalizeSignalingContent keeps call type for incoming call UI", () => {
  const signal = normalizeSignalingContent({
    action: "CALLING",
    roomId: "room_1",
    token: "callee-token",
    callType: "video",
  });

  assert.equal(signal?.action, "CALLING");
  assert.equal(signal?.roomId, "room_1");
  assert.equal(signal?.token, "callee-token");
  assert.equal(signal?.callType, "video");
});

test("HTTP resource APIs require httpUrl instead of falling back to websocket", async () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });

  await assert.rejects(
    () => im.group.list(),
    (err) => err instanceof IMConfigError && err.message === "HTTP API requires httpUrl",
  );
});

test("HttpTransport attaches generated X-Request-Id to every request", async () => {
  const calls = [];
  const http = new HttpTransport({
    baseUrl: "http://im.test",
    requestIdFactory: () => "req_http_1",
    fetchImpl: async (input, init) => {
      calls.push({ input, init });
      return new Response(JSON.stringify({ code: 0, msg: "ok", data: { ok: true } }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  });

  await http.get("/api/conversation/list");

  assert.equal(calls[0].init.headers["X-Request-Id"], "req_http_1");
});

test("WsTransport attaches generated _requestId to request frames", async () => {
  const ws = new WsTransport({
    requestIdFactory: () => "req_ws_1",
  });
  const sentFrames = [];
  ws.send = (frame) => {
    sentFrames.push(frame);
    return true;
  };

  const pending = ws.request("chat.send", { toUserId: "u2" });
  pending.catch(() => {});

  assert.equal(sentFrames[0]._requestId, "req_ws_1");
});

test("HttpTransport rejects non-envelope HTTP responses at the protocol boundary", async () => {
  const http = new HttpTransport({
    baseUrl: "http://im.test",
    fetchImpl: async () => new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  });

  await assert.rejects(
    () => http.get("/api/conversation/list"),
    (err) => err instanceof IMProtocolError && err.message === "Invalid HTTP envelope",
  );
});

test("HttpTransport times out hanging HTTP requests", async () => {
  const http = new HttpTransport({
    baseUrl: "http://im.test",
    requestTimeout: 20,
    fetchImpl: (_input, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    }),
  });

  await assert.rejects(
    () => http.get("/api/user/info", { userId: "u1" }),
    (err) => err instanceof IMTimeoutError && err.message === "HTTP request timeout",
  );
});


test("friend receivedApplyList uses received apply endpoint with pending filter", async () => {
  const { http, calls } = createHttpCapture({ applies: [{ fromUserId: "alice", toUserId: "bob", handleResult: 0 }] });
  const friendApi = new FriendAPI(http);

  const applies = await friendApi.receivedApplyList();

  assert.deepEqual(applies, [{ fromUserId: "alice", toUserId: "bob", handleResult: 0 }]);
  assert.deepEqual(calls[0], {
    method: "GET",
    path: "/api/friend/apply/received",
    query: { onlyPending: true },
  });
});

test("friend and group mutation payloads match backend HTTP contract", async () => {
  const { http, calls } = createHttpCapture({ status: "OK" });
  const friendApi = new FriendAPI(http);
  const groupApi = new GroupAPI(http);

  await friendApi.remove("u2");
  await friendApi.black("u3");
  await groupApi.kick("g1", "u4");
  await groupApi.muteAll("g1", true);

  assert.deepEqual(calls[0], { method: "POST", path: "/api/friend/remove", body: { friendUserId: "u2" } });
  assert.deepEqual(calls[1], { method: "POST", path: "/api/friend/black", body: { blockedUserId: "u3" } });
  assert.deepEqual(calls[2], { method: "POST", path: "/api/group/kick", body: { groupId: "g1", targetUserId: "u4" } });
  assert.deepEqual(calls[3], { method: "POST", path: "/api/group/mute/all", body: { groupId: "g1", mute: true } });
});

test("system API uses system message HTTP endpoints", async () => {
  const { http, calls } = createHttpCapture((method, path) => {
    if (path === "/api/system/channels") return { channels: [{ channelId: "wallet", channelName: "钱包通知" }] };
    if (path === "/api/system/messages") return { messages: [{ messageId: "m1", channelId: "wallet", title: "余额变动" }] };
    if (path === "/api/system/messages/unread-count") return { count: 1, byChannel: { wallet: 1 } };
    if (path === "/api/admin/system/messages/publish") return { message: { messageId: "m2", channelId: "wallet", title: "余额变动", createdAt: 1 } };
    return {};
  });
  const systemApi = new SystemAPI(http);

  assert.deepEqual(await systemApi.channels(), [{ channelId: "wallet", channelName: "钱包通知" }]);
  assert.deepEqual(await systemApi.messages({ channelId: "wallet", onlyUnread: true }), [{ messageId: "m1", channelId: "wallet", title: "余额变动" }]);
  assert.deepEqual(await systemApi.unreadCount(), { count: 1, byChannel: { wallet: 1 } });
  assert.deepEqual(await systemApi.publish({
    channelId: "wallet",
    title: "余额变动",
    content: "你的账户收到一笔入账",
    targetUserIds: ["u1"],
  }), { messageId: "m2", channelId: "wallet", title: "余额变动", createdAt: 1 });

  assert.deepEqual(calls.map((c) => `${c.method} ${c.path}`), [
    "GET /api/system/channels",
    "GET /api/system/messages",
    "GET /api/system/messages/unread-count",
    "POST /api/admin/system/messages/publish",
  ]);
  assert.deepEqual(calls[1].query, { channelId: "wallet", onlyUnread: true });
  assert.deepEqual(calls[3].body.targetUserIds, ["u1"]);
});

test("transport.request rejects immediately when websocket is not connected", async () => {
  const transport = new WsTransport({ requestTimeout: 10_000 });

  await assert.rejects(
    () => transport.request("chat.seq", { conversationId: "c1" }),
    (err) => err instanceof IMConnectionError && err.message === "Not connected",
  );
  assert.equal(transport.requestManager.pendingCount, 0);
});

test("realtime login rejects immediately when websocket is not connected", async () => {
  const transport = new WsTransport({ requestTimeout: 20 });
  const api = new UserAPI(transport, { get: async () => ({}), post: async () => ({}) });

  await assert.rejects(
    () => api.login("u1"),
    (err) => err instanceof IMConnectionError && err.message === "Not connected",
  );
  assert.equal(transport.requestManager.pendingCount, 0);
});

test("user.login sends web platform by default", async () => {
  const { transport, sentFrames } = createCapturingTransport({
    token: "access-1",
    refreshToken: "refresh-1",
  });
  const api = new UserAPI(transport, { get: async () => ({}), post: async () => ({}) });

  await api.login("u1", "pw");

  assert.deepEqual(sentFrames[0], {
    op: "login",
    seq: 1,
    userId: "u1",
    platformId: 5,
    password: "pw",
  });
});

test("user.me fetches authenticated profile over HTTP", async () => {
  const calls = [];
  const api = new UserAPI(
    new WsTransport({ requestTimeout: 20 }),
    {
      get: async (path, query) => {
        calls.push({ method: "GET", path, query });
        return { userId: "u1", nickname: "Alice" };
      },
      post: async () => ({}),
    },
  );

  const profile = await api.me();

  assert.deepEqual(profile, { userId: "u1", nickname: "Alice" });
  assert.deepEqual(calls[0], { method: "GET", path: "/api/user/me", query: undefined });
});

test("user.updateAvatar uploads file, updates faceUrl, and returns refreshed profile", async () => {
  const calls = [];
  const api = new UserAPI(
    new WsTransport({ requestTimeout: 20 }),
    {
      get: async (path, query) => {
        calls.push({ method: "GET", path, query });
        return { userId: "u1", nickname: "Alice", faceUrl: "https://files.test/avatar.png" };
      },
      post: async (path, body) => {
        calls.push({ method: "POST", path, body });
        return {};
      },
    },
    async (fileName, body, mimeType) => {
      calls.push({ method: "UPLOAD", fileName, body, mimeType });
      return { fileId: "f1", fileUrl: "https://files.test/avatar.png" };
    },
  );

  const profile = await api.updateAvatar(new Blob(["abc"], { type: "image/png" }), "avatar.png");

  assert.deepEqual(profile, { userId: "u1", nickname: "Alice", faceUrl: "https://files.test/avatar.png" });
  assert.equal(calls[0].method, "UPLOAD");
  assert.equal(calls[0].fileName, "avatar.png");
  assert.equal(calls[0].mimeType, "image/png");
  assert.deepEqual(calls[1], {
    method: "POST",
    path: "/api/user/update",
    body: { faceUrl: "https://files.test/avatar.png" },
  });
  assert.deepEqual(calls[2], { method: "GET", path: "/api/user/me", query: undefined });
});

test("sdk.login stores returned tokens and emits tokenChanged", async () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });
  const tokens = [];
  im.on("tokenChanged", (tokenPair) => tokens.push(tokenPair));
  im.user.login = async () => ({
    op: "login_ack",
    seq: 1,
    code: 0,
    data: {
      token: "access-1",
      refreshToken: "refresh-1",
      expiresIn: 7200,
    },
  });

  const result = await im.login("u1", "pw");

  assert.equal(result.token, "access-1");
  assert.equal(im.token, "access-1");
  assert.equal(im.refreshToken, "refresh-1");
  assert.deepEqual(tokens, [{ token: "access-1", refreshToken: "refresh-1", expiresIn: 7200 }]);
});

test("sdk.clearTokens removes in-memory tokens and emits empty tokenChanged", async () => {
  const changed = [];
  const im = createIM({
    wsUrl: "ws://example.test/ws",
    onTokenChanged: (tokens) => changed.push(tokens),
  });
  const emitted = [];
  im.on("tokenChanged", (tokenPair) => emitted.push(tokenPair));
  im.user.login = async () => ({
    op: "login_ack",
    seq: 1,
    code: 0,
    data: {
      token: "access-1",
      refreshToken: "refresh-1",
    },
  });

  await im.login("u1", "pw");
  im.clearTokens();

  assert.equal(im.token, null);
  assert.equal(im.refreshToken, null);
  assert.deepEqual(changed.at(-1), {});
  assert.deepEqual(emitted.at(-1), {});
});

test("sdk.waitConnected resolves on connected state and times out with typed error", async () => {
  const im = createIM({ wsUrl: "ws://example.test/ws", connectTimeout: 20 });
  const waiting = im.waitConnected(100);

  im.transport.bus.emit("stateChanged", "connected");

  await waiting;

  const disconnected = createIM({ wsUrl: "ws://example.test/ws", connectTimeout: 5 });
  await assert.rejects(
    () => disconnected.waitConnected(),
    (err) => err instanceof IMTimeoutError && err.message === "Wait connected timeout",
  );
});

test("sdk.ready starts transport connect before waiting", async () => {
  const im = createIM({ wsUrl: "ws://example.test/ws", connectTimeout: 100 });
  let connectedUrl = "";
  im.transport.connect = (url) => {
    connectedUrl = url;
    setTimeout(() => im.transport.bus.emit("stateChanged", "connected"), 0);
  };

  await im.ready();

  assert.equal(connectedUrl, "ws://example.test/ws");
});

test("sdk syncs missed messages after reconnect using host cursors", async () => {
  const im = createIM({
    wsUrl: "ws://example.test/ws",
    httpUrl: "http://example.test",
    syncOnReconnect: true,
    syncConversations: () => [{ conversationId: "c1", lastSeq: 2 }],
  });
  const synced = [];
  const batches = [];
  im.on("reconnectSync", (result) => synced.push(result));
  im.on("messageBatch", (msgs) => batches.push(msgs));
  im.message.sync = async (conversationId, lastSeq) => [{
    conversationId,
    maxSeq: lastSeq + 1,
    messages: [{
      messageId: "m3",
      conversationId,
      messageSeq: lastSeq + 1,
      sequenceId: lastSeq + 1,
      timestamp: 100,
      fromUserId: "u2",
      contentType: 1,
      content: JSON.stringify({ text: "missed" }),
      status: 1,
    }],
  }];

  im.transport.bus.emit("stateChanged", "connected");
  im.transport.bus.emit("stateChanged", "disconnected");
  im.transport.bus.emit("stateChanged", "connected");
  await new Promise((resolve) => setTimeout(resolve, 25));

  assert.equal(synced.length, 1);
  assert.equal(synced[0].conversationId, "c1");
  assert.deepEqual(batches[0].map((m) => m.messageId), ["m3"]);
});

test("transport heartbeat carries refresh token and updates token from heartbeat ack", async () => {
  let token = "access-old";
  let refreshToken = "refresh-old";
  const sentFrames = [];
  const tokenUpdates = [];
  const transport = new WsTransport({
    getToken: () => token,
    getRefreshToken: () => refreshToken,
    onTokenChanged: (next) => {
      tokenUpdates.push(next);
      token = next.token ?? token;
      refreshToken = next.refreshToken ?? refreshToken;
    },
  });

  transport.ws = {
    readyState: WebSocket.OPEN,
    send(data) {
      sentFrames.push(JSON.parse(data));
    },
  };
  transport.send({ op: "heartbeat", seq: 0 });
  transport.handleMessage(JSON.stringify({
    op: "heartbeat_ack",
    seq: 0,
    code: 0,
    data: {
      token: "access-new",
      refreshToken: "refresh-new",
    },
  }));

  assert.equal(sentFrames[0].Authorization, "access-old");
  assert.equal(sentFrames[0].refreshToken, "refresh-old");
  assert.deepEqual(tokenUpdates, [{ token: "access-new", refreshToken: "refresh-new" }]);
});

test("sdk emits typed and raw push events for revoke and unknown pushes", () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });
  const revoked = [];
  const rawPushes = [];
  im.on("messageRevoked", (event) => revoked.push(event));
  im.on("push", (event) => rawPushes.push(event));

  im.transport.handleMessage(JSON.stringify({
    op: "msg_revoke",
    code: 0,
    data: {
      conversationId: "c1",
      seq: 7,
      revokerId: "u1",
    },
  }));
  im.transport.handleMessage(JSON.stringify({
    op: "conversation.updated",
    data: { conversationId: "c1" },
  }));

  assert.deepEqual(revoked, [{ conversationId: "c1", seq: 7, revokerId: "u1" }]);
  assert.deepEqual(rawPushes.map((p) => p.op), ["msg_revoke", "conversation.updated"]);
});

test("sdk emits systemMessage for system message pushes", () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });
  const messages = [];
  const rawPushes = [];
  im.on("systemMessage", (message) => messages.push(message));
  im.on("push", (event) => rawPushes.push(event));

  im.transport.handleMessage(JSON.stringify({
    op: "system.message",
    data: {
      messageId: "m1",
      channelId: "wallet",
      title: "余额变动",
      createdAt: 1,
    },
  }));

  assert.deepEqual(messages, [{ messageId: "m1", channelId: "wallet", title: "余额变动", createdAt: 1 }]);
  assert.equal(rawPushes[0].op, "system.message");
});

test("sdk treats offline bare message payloads as message pushes", () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });
  const messages = [];
  const rawPushes = [];
  im.on("message", (msg) => messages.push(msg));
  im.on("push", (event) => rawPushes.push(event));

  im.transport.handleMessage(JSON.stringify({
    messageId: "m-offline",
    conversationId: "c1",
    sequenceId: 11,
    content: "offline",
  }));

  assert.equal(messages[0].messageId, "m-offline");
  assert.equal(rawPushes[0].op, "message");
  assert.equal(rawPushes[0].data.messageId, "m-offline");
});

test("sdk emits typed call signal events from signal messages", () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });
  const incoming = [];
  const rejected = [];
  const allSignals = [];
  im.on("callIncoming", (event) => incoming.push(event));
  im.on("callRejected", (event) => rejected.push(event));
  im.on("callSignal", (event) => allSignals.push(event));

  im.transport.handleMessage(JSON.stringify({
    op: "message",
    data: {
      messageId: "m-call-1",
      conversationId: "single_u1_u2",
      sequenceId: 1,
      messageSeq: 1,
      timestamp: 10,
      fromUserId: "u1",
      toUserId: "u2",
      contentType: MessageContentType.SIGNAL,
      content: JSON.stringify({
        action: "CALLING",
        roomId: "room_1",
        token: "callee-token",
        callType: "video",
      }),
    },
  }));
  im.transport.handleMessage(JSON.stringify({
    op: "message",
    data: {
      messageId: "m-call-2",
      conversationId: "single_u1_u2",
      sequenceId: 2,
      messageSeq: 2,
      timestamp: 11,
      fromUserId: "u1",
      toUserId: "u2",
      contentType: MessageContentType.SIGNAL,
      content: JSON.stringify({
        action: "REJECT",
        roomId: "room_1",
        reason: "busy",
      }),
    },
  }));

  assert.equal(incoming.length, 1);
  assert.equal(incoming[0].signal.action, "CALLING");
  assert.equal(incoming[0].signal.callType, "video");
  assert.equal(rejected.length, 1);
  assert.equal(rejected[0].signal.reason, "busy");
  assert.deepEqual(allSignals.map((event) => event.signal.action), ["CALLING", "REJECT"]);
});



test("parseMessageContent exposes typed message content contracts", () => {
  const text = parseMessageContent({ contentType: MessageContentType.TEXT, content: JSON.stringify({ text: "hello" }) });
  const image = parseMessageContent({
    contentType: MessageContentType.IMAGE,
    content: JSON.stringify({
      sourcePicture: { url: "http://img/source.png", width: 640, height: 480, type: "image/png", fileSize: 100 },
      snapshotPicture: { url: "http://img/thumb.png", width: 160, height: 120, type: "image/png", fileSize: 20 },
    }),
  });
  const broken = parseMessageContent({ contentType: MessageContentType.FILE, content: "{bad-json" });

  assert.equal(text.type, MessageContentType.TEXT);
  assert.equal(text.content.text, "hello");
  assert.equal(image.type, MessageContentType.IMAGE);
  assert.equal(image.content.snapshotPicture.url, "http://img/thumb.png");
  assert.equal(broken.type, "unknown");
  assert.equal(broken.raw, "{bad-json");
});


test("sdk batches websocket message pushes before emitting messageBatch", async () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });
  const batches = [];
  const singles = [];
  im.on("messageBatch", (msgs) => batches.push(msgs));
  im.on("message", (msg) => singles.push(msg));

  im.transport.handleMessage(JSON.stringify({
    op: "message",
    data: { messageId: "m1", conversationId: "c1", messageSeq: 1, content: "one" },
  }));
  im.transport.handleMessage(JSON.stringify({
    op: "message",
    data: { messageId: "m2", conversationId: "c1", messageSeq: 2, content: "two" },
  }));

  assert.equal(batches.length, 0);
  await new Promise((resolve) => setTimeout(resolve, 25));

  assert.equal(batches.length, 1);
  assert.deepEqual(batches[0].map((m) => m.messageId), ["m1", "m2"]);
  assert.deepEqual(singles.map((m) => m.messageId), ["m1", "m2"]);
});

test("sdk does not emit duplicate message pushes with the same message id", async () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });
  const batches = [];
  const singles = [];
  im.on("messageBatch", (msgs) => batches.push(msgs));
  im.on("message", (msg) => singles.push(msg));

  const payload = {
    op: "message",
    data: { messageId: "m-dup", conversationId: "c1", messageSeq: 9, content: "same" },
  };
  im.transport.handleMessage(JSON.stringify(payload));
  im.transport.handleMessage(JSON.stringify(payload));

  await new Promise((resolve) => setTimeout(resolve, 25));

  assert.deepEqual(singles.map((m) => m.messageId), ["m-dup"]);
  assert.deepEqual(batches.map((batch) => batch.map((m) => m.messageId)), [["m-dup"]]);
});


test("http transport calls default fetch with the global object binding", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = function (url, init) {
    if (this !== globalThis) {
      throw new TypeError("Illegal invocation");
    }
    calls.push({ url: String(url), init });
    return Promise.resolve(new Response(JSON.stringify({ code: 0, msg: "ok", data: { ok: true } }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
  };

  try {
    const http = new HttpTransport({ baseUrl: "http://127.0.0.1:8084" });
    const result = await http.get("/api/conversation/list");

    assert.deepEqual(result, { ok: true });
    assert.equal(calls[0].url, "http://127.0.0.1:8084/api/conversation/list");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("file.upload signs, uploads to object storage, then completes through HTTP transport", async () => {
  const calls = [];
  const http = new HttpTransport({
    baseUrl: "http://127.0.0.1:8084",
    getToken: () => "access-1",
    fetchImpl: async (url, init) => {
      calls.push({ url: String(url), init });
      if (String(url) === "https://oss.test/upload-a") {
        return new Response("", { status: 200 });
      }
      if (String(url).endsWith("/api/file/upload/sign")) {
        return new Response(JSON.stringify({
          code: 0,
          msg: "ok",
          data: {
            fileId: "f1",
            uploadUrl: "https://oss.test/upload-a",
            headers: { "Content-Type": "text/plain" },
          },
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return new Response(JSON.stringify({
        code: 0,
        msg: "ok",
        data: {
          fileUrl: "http://files/a.txt",
          fileId: "f1",
          fileName: "a.txt",
          mimeType: "text/plain",
          fileSize: "3",
        },
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    },
  });

  const result = await http.uploadFile("a.txt", new Uint8Array([1, 2, 3]), "text/plain");

  assert.equal(result.fileUrl, "http://files/a.txt");
  assert.equal(calls[0].url, "http://127.0.0.1:8084/api/file/upload/sign");
  assert.equal(calls[0].init.method, "POST");
  assert.equal(calls[0].init.headers.Authorization, "Bearer access-1");
  assert.deepEqual(JSON.parse(calls[0].init.body), {
    fileName: "a.txt",
    fileSize: 3,
    mimeType: "text/plain",
  });
  assert.equal(calls[1].url, "https://oss.test/upload-a");
  assert.equal(calls[1].init.method, "PUT");
  assert.equal(calls[1].init.headers["Content-Type"], "text/plain");
  assert.deepEqual(Array.from(new Uint8Array(calls[1].init.body)), [1, 2, 3]);
  assert.equal(calls[2].url, "http://127.0.0.1:8084/api/file/upload/complete");
  assert.deepEqual(JSON.parse(calls[2].init.body), { fileId: "f1" });
});

test("file.multipartUpload signs part and uploads bytes to object storage", async () => {
  const calls = [];
  const http = new HttpTransport({
    baseUrl: "http://127.0.0.1:8084/",
    fetchImpl: async (url, init) => {
      calls.push({ url: String(url), init });
      if (String(url) === "https://oss.test/part-2") {
        return new Response("", { status: 200, headers: { ETag: "etag-1" } });
      }
      return new Response(JSON.stringify({
        code: 0,
        msg: "ok",
        data: { uploadUrl: "https://oss.test/part-2", headers: {} },
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    },
  });

  const etag = await http.uploadPart("upload-1", 2, new Uint8Array([9, 8]));

  assert.equal(etag, "etag-1");
  assert.equal(calls[0].url, "http://127.0.0.1:8084/api/file/multipart/part-sign");
  assert.equal(calls[0].init.method, "POST");
  assert.deepEqual(JSON.parse(calls[0].init.body), { uploadId: "upload-1", partNumber: 2 });
  assert.equal(calls[1].url, "https://oss.test/part-2");
  assert.equal(calls[1].init.method, "PUT");
  assert.deepEqual(Array.from(new Uint8Array(calls[1].init.body)), [9, 8]);
});

test("sdk file api requires httpUrl instead of falling back to websocket", async () => {
  const im = createIM({ wsUrl: "ws://example.test/ws" });

  await assert.rejects(
    () => im.file.upload("a.txt", new Uint8Array([1]), "text/plain"),
    (err) => err instanceof IMConfigError && err.message === "File API requires httpUrl",
  );
});

test("conversation.read sends readSeq over HTTP and returns unread count", async () => {
  const { http, calls } = createHttpCapture({ conversationId: "c1", unreadCount: 0 });
  const { ConversationAPI } = await import("../dist/api/conversation.js");
  const api = new ConversationAPI(http);

  const result = await api.read("c1", 12);

  assert.deepEqual(result, { conversationId: "c1", unreadCount: 0 });
  assert.deepEqual(calls[0], {
    method: "POST",
    path: "/api/conversation/read",
    body: { conversationId: "c1", readSeq: 12 },
  });
});

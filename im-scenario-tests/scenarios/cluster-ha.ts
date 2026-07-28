import { execFileSync } from "node:child_process";
import { lstatSync, readFileSync, realpathSync } from "node:fs";
import { resolve } from "node:path";
import { assertOk } from "../src/assertions.js";
import { waitFor, waitForAsync } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { readNumberArg } from "../src/cli.js";
import { loadScenarioConfig } from "../src/config.js";
import { hasTextContent, isSignalingContent } from "../src/message-content.js";
import {
  CLUSTER_NODE_DEFAULTS,
} from "../src/defaults.js";
import {
  GROUP_JOIN_VERIFICATION_CODE,
  SCENARIO_CONTENT_TYPE,
  SCENARIO_OP,
  SCENARIO_PUSH_OP,
} from "../src/protocol.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type {
  GroupCallJoinResult,
  GroupCallSession,
  GroupInfo,
  MessagePush,
  SendMessageAck,
} from "../src/types.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);

const node1HttpUrl = process.env.IM_SCENARIO_NODE1_HTTP_URL ?? CLUSTER_NODE_DEFAULTS.node1.httpUrl;
const node1WsUrl = process.env.IM_SCENARIO_NODE1_WS_URL ?? CLUSTER_NODE_DEFAULTS.node1.wsUrl;
const node2HttpUrl = process.env.IM_SCENARIO_NODE2_HTTP_URL ?? CLUSTER_NODE_DEFAULTS.node2.httpUrl;
const node2WsUrl = process.env.IM_SCENARIO_NODE2_WS_URL ?? CLUSTER_NODE_DEFAULTS.node2.wsUrl;
const holdMs = readNumberArg("hold-ms", 0);
const groupCallMaxParticipants = readPositiveEnv("IM_SCENARIO_GROUP_CALL_MAX_PARTICIPANTS", 16);
const callTimeoutSeconds = readPositiveEnv("IM_SCENARIO_CALL_TIMEOUT_SECONDS", 30);
const node1StopControl = loadNode1StopControl();

const users: ScenarioUser[] = [];

try {
  reporter.step("creating cluster test users through node-1 HTTP");
  const sender = createUser("Sender", node1HttpUrl, node1WsUrl);
  const receiverPrimary = createUser("Receiver Primary", node2HttpUrl, node2WsUrl);
  await sender.register();
  await receiverPrimary.register();
  users.push(sender, receiverPrimary);
  assertOk(sender.userId && receiverPrimary.userId, "users were not registered");
  reporter.metric("senderUserId", sender.userId);
  reporter.metric("receiverUserId", receiverPrimary.userId);

  reporter.step("connecting sender to node-1 and receiver to node-2");
  await Promise.all([sender.connectAndLogin(), receiverPrimary.connectAndLogin()]);

  reporter.step("opening a second receiver websocket session on node-1");
  const receiverSecondary = createExistingUser(
    "Receiver Secondary",
    node1HttpUrl,
    node1WsUrl,
    receiverPrimary.userId,
    receiverPrimary.token,
    receiverPrimary.refreshToken,
  );
  await receiverSecondary.connectAndLogin();
  users.push(receiverSecondary);

  assertOk(groupCallMaxParticipants >= 2,
    "IM_SCENARIO_GROUP_CALL_MAX_PARTICIPANTS must be at least 2");
  reporter.step(`creating ${groupCallMaxParticipants - 1} additional group-call contenders`);
  const groupCallJoiners: ScenarioUser[] = [receiverPrimary];
  for (let index = 0; index < groupCallMaxParticipants - 1; index++) {
    const useNode1 = index % 2 === 0;
    const contender = createUser(
      `Call Contender ${index + 1}`,
      useNode1 ? node1HttpUrl : node2HttpUrl,
      useNode1 ? node1WsUrl : node2WsUrl,
    );
    await contender.register();
    await contender.connectAndLogin();
    users.push(contender);
    groupCallJoiners.push(contender);
  }

  reporter.step("creating and approving friend relation across nodes");
  await sender.http.post("/api/friend/apply", {
    toUserId: receiverPrimary.userId,
    reqMsg: "cluster-ha friend",
  });
  await receiverPrimary.http.post("/api/friend/approve", {
    fromUserId: sender.userId,
    agreed: true,
    handleMsg: "ok",
  });

  reporter.step("sending cross-node single chat from node-1 to node-2");
  const singleText = `cluster-ha single ${suffix}`;
  const singleAck = await sender.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND, {
    toUserId: receiverPrimary.userId,
    clientMsgId: nextClientMsgId("cluster-single"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: singleText },
  });
  const singleAckData = singleAck.data;
  assertOk(singleAckData?.conversationId, "single chat did not return conversationId");
  await assertMessagePush(receiverPrimary, singleText, "primary single push");
  await assertMessagePush(receiverSecondary, singleText, "secondary single push");

  reporter.step("revoking cross-node single chat and checking all receiver sessions");
  const primaryRevokeCursor = receiverPrimary.ws.markPushCursor();
  const secondaryRevokeCursor = receiverSecondary.ws.markPushCursor();
  await sender.http.post("/api/msg/revoke", {
    conversationId: singleAckData.conversationId,
    messageSeq: singleAckData.seq,
  });
  await assertRevokePushAfter(
    receiverPrimary,
    primaryRevokeCursor,
    singleAckData.conversationId,
    singleAckData.seq,
    sender.userId,
    "primary revoke push",
  );
  await assertRevokePushAfter(
    receiverSecondary,
    secondaryRevokeCursor,
    singleAckData.conversationId,
    singleAckData.seq,
    sender.userId,
    "secondary revoke push",
  );

  reporter.step("creating group on node-1 with receiver on node-2");
  const group = await sender.http.post<GroupInfo>("/api/group/create", {
    groupName: `cluster_ha_${suffix}`,
    members: groupCallJoiners.map((user) => user.userId),
    needVerification: GROUP_JOIN_VERIFICATION_CODE.DIRECT,
  });
  assertOk(group.groupId, "group.create did not return groupId");
  reporter.metric("groupId", group.groupId);

  reporter.step("sending cross-node group chat from node-1 to node-2");
  const groupText = `cluster-ha group ${suffix}`;
  const groupAck = await sender.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND_GROUP, {
    groupId: group.groupId,
    clientMsgId: nextClientMsgId("cluster-group"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: groupText },
  });
  assertOk(groupAck.data?.seq !== undefined, "group chat did not return seq");
  await assertGroupPush(receiverPrimary, group.groupId, groupText, "primary group push");
  await assertGroupPush(receiverSecondary, group.groupId, groupText, "secondary group push");

  reporter.step("closing one same-platform session while its node remains live");
  receiverSecondary.close();
  const survivorCursor = receiverPrimary.ws.markPushCursor();
  const survivorText = `cluster-ha surviving-platform ${suffix}`;
  await sender.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND, {
    toUserId: receiverPrimary.userId,
    clientMsgId: nextClientMsgId("cluster-surviving-platform"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: survivorText },
  });
  await assertMessagePushAfter(receiverPrimary, survivorCursor, survivorText, "surviving same-platform push");

  reporter.step(`racing ${groupCallJoiners.length} group-call joins against cap ${groupCallMaxParticipants}`);
  const started = await sender.http.post<GroupCallSession>("/api/group/call/start", {
    groupId: group.groupId,
    callType: "voice",
    clientMsgId: nextClientMsgId("cluster-group-call"),
  });
  assertOk(started.active && started.participantCount === 1,
    `group call did not start with one participant: ${JSON.stringify(started)}`);

  const joinResults = await Promise.allSettled(groupCallJoiners.map((user) =>
    user.http.post<GroupCallJoinResult>("/api/group/call/join", { groupId: group.groupId })));
  const joinedCount = joinResults.filter((result) => result.status === "fulfilled").length;
  const rejectedResults = joinResults.filter((result) => result.status === "rejected");
  const rejectedCount = rejectedResults.length;
  assertOk(joinedCount === groupCallMaxParticipants - 1,
    `expected ${groupCallMaxParticipants - 1} successful concurrent joins, got ${joinedCount}`);
  assertOk(rejectedCount === 1, `expected exactly one full-call rejection, got ${rejectedCount}`);
  assertOk(rejectedResults.every((result) => errorMessage(result.reason).includes("group call is full")),
    `concurrent join rejection was not caused by the configured cap: ${rejectedResults.map((result) => errorMessage(result.reason)).join("; ")}`);

  const atCapacity = await sender.http.get<GroupCallSession>("/api/group/call/active", {
    groupId: group.groupId,
  });
  assertOk(atCapacity.participantCount === groupCallMaxParticipants,
    `group call exceeded or missed cap ${groupCallMaxParticipants}: ${atCapacity.participantCount}`);
  reporter.metric("groupCallParticipantCount", atCapacity.participantCount);
  await sender.http.post("/api/group/call/end", {
    groupId: group.groupId,
    clientMsgId: nextClientMsgId("cluster-group-call"),
  });

  reporter.step("starting a single call on node-1 before its guarded shutdown");
  const callingCursor = receiverPrimary.ws.markPushCursor();
  const call = await sender.ws.request<{ roomId?: string; status?: string }>(SCENARIO_OP.CHAT_SEND, {
    toUserId: receiverPrimary.userId,
    clientMsgId: nextClientMsgId("cluster-single-call"),
    _ct: "signal",
    content: { action: "INVITE", callType: "voice" },
  });
  assertOk(call.data?.roomId && call.data.status === "CALLING",
    `single call did not enter CALLING: ${JSON.stringify(call.data)}`);
  const roomId = call.data.roomId;
  await assertSignalPushAfter(receiverPrimary, callingCursor, roomId, "CALLING", "single-call invite");

  const timeoutCursor = receiverPrimary.ws.markPushCursor();
  reporter.step("validating the explicit node-1 stop control and sending SIGTERM");
  await stopNode1(node1StopControl);

  reporter.step("checking node-2 remains live after node-1 stops");
  const node2Health = await fetchHealth(node2HttpUrl);
  assertOk(node2Health.nodeId === "node-2", `expected live node-2 health, got ${JSON.stringify(node2Health)}`);

  reporter.step("checking the surviving same-platform session remains routable for timeout delivery");
  await waitFor(() => receiverPrimary.ws.pushesAfter(timeoutCursor).find((push) => {
    const data = push.data as MessagePush | undefined;
    return push.op === SCENARIO_PUSH_OP.MESSAGE &&
      data?.contentType === SCENARIO_CONTENT_TYPE.SIGNAL &&
      isSignalingContent(data.content, { roomId, action: "TIMEOUT" });
  }), {
    timeoutMs: callTimeoutSeconds * 1_000 + config.requestTimeoutMs,
    intervalMs: config.pollIntervalMs,
    description: `node-2 timeout delivery for room ${roomId}`,
    onTimeout: () => `pushes=${JSON.stringify(receiverPrimary.ws.pushesAfter(timeoutCursor))}`,
  });
  reporter.metric("singleCallTimeoutRoomId", roomId);

  if (holdMs > 0) {
    reporter.step(`holding websocket sessions for ${holdMs}ms`);
    await new Promise((resolve) => setTimeout(resolve, holdMs));
  }

  reporter.metric("node1Ws", node1WsUrl);
  reporter.metric("node2Ws", node2WsUrl);
  reporter.finish();
} finally {
  for (const user of users) user.close();
}

function createUser(nickname: string, httpUrl: string, wsUrl: string): ScenarioUser {
  return new ScenarioUser({
    httpUrl,
    wsUrl,
    requestTimeoutMs: config.requestTimeoutMs,
    password: config.defaultPassword,
    nickname: `${nickname} Cluster HA ${suffix}`,
  });
}

function createExistingUser(
  nickname: string,
  httpUrl: string,
  wsUrl: string,
  userId: string,
  token: string | undefined,
  refreshToken: string | undefined,
): ScenarioUser {
  const user = createUser(nickname, httpUrl, wsUrl);
  user.userId = userId;
  user.token = token;
  user.refreshToken = refreshToken;
  return user;
}

async function assertMessagePush(user: ScenarioUser, expectedText: string, description: string): Promise<void> {
  await user.ws.waitForPush((push) => {
    const data = push.data as MessagePush | undefined;
    return push.op === SCENARIO_PUSH_OP.MESSAGE && messageContains(data, expectedText);
  }, description);
}

async function assertMessagePushAfter(
  user: ScenarioUser,
  cursor: number,
  expectedText: string,
  description: string,
): Promise<void> {
  await user.ws.waitForPushAfter(cursor, (push) => {
    const data = push.data as MessagePush | undefined;
    return push.op === SCENARIO_PUSH_OP.MESSAGE && messageContains(data, expectedText);
  }, description);
}

async function assertGroupPush(
  user: ScenarioUser,
  groupId: string,
  expectedText: string,
  description: string,
): Promise<void> {
  await user.ws.waitForPush((push) => {
    const data = push.data as MessagePush | undefined;
    return push.op === SCENARIO_PUSH_OP.MESSAGE && data?.groupId === groupId && messageContains(data, expectedText);
  }, description);
}

async function assertRevokePushAfter(
  user: ScenarioUser,
  cursor: number,
  conversationId: string,
  seq: number,
  revokerId: string,
  description: string,
): Promise<void> {
  await user.ws.waitForPushAfter(cursor, (push) => {
    const data = push.data as { conversationId?: string; seq?: number; revokerId?: string } | undefined;
    return push.op === SCENARIO_PUSH_OP.MESSAGE_REVOKED &&
      data?.conversationId === conversationId &&
      data?.seq === seq &&
      data?.revokerId === revokerId;
  }, description);
}

function messageContains(message: MessagePush | undefined, expectedText: string): boolean {
  if (!message) return false;
  return hasTextContent(message.content, expectedText);
}

interface NodeStopControl {
  pidFile: string;
  pid: number;
  wsPort: number;
  httpPort: number;
}

interface HealthPayload {
  nodeId?: string;
  process?: string;
}

function loadNode1StopControl(): NodeStopControl {
  const configured = process.env.IM_SCENARIO_NODE1_PID_FILE;
  if (!configured) {
    throw new Error(
      "[PREREQUISITE] cluster-ha shutdown coverage requires explicit opt-in: " +
      "set IM_SCENARIO_NODE1_PID_FILE to the node-1 PID file (for bin/start-cluster.sh: bin/pids/node-1.pid)",
    );
  }
  const node1Http = new URL(node1HttpUrl);
  const node1Ws = new URL(node1WsUrl);
  assertOk(isLoopback(node1Http.hostname) && isLoopback(node1Ws.hostname),
    "IM_SCENARIO_NODE1_PID_FILE may only stop a node reached through a loopback node-1 URL");
  const configuredPath = resolve(configured);
  let stat;
  try {
    stat = lstatSync(configuredPath);
  } catch (error) {
    throw new Error(`[PREREQUISITE] node-1 PID file is unavailable: ${configuredPath}: ${errorMessage(error)}`);
  }
  assertOk(stat.isFile() && !stat.isSymbolicLink(),
    `node-1 PID path must be a regular, non-symlink file: ${configuredPath}`);
  const pidFile = realpathSync(configuredPath);
  const rawPid = readFileSync(pidFile, "utf8").trim();
  assertOk(/^[1-9]\d*$/.test(rawPid), `node-1 PID file must contain one positive integer: ${pidFile}`);
  const pid = Number(rawPid);
  assertOk(Number.isSafeInteger(pid) && pid > 1, `unsafe node-1 PID in ${pidFile}: ${rawPid}`);
  const wsPort = urlPort(node1Ws);
  const httpPort = urlPort(node1Http);
  validateControlledProcess(pid, wsPort, httpPort);
  return { pidFile, pid, wsPort, httpPort };
}

async function stopNode1(control: NodeStopControl): Promise<void> {
  const currentPid = readFileSync(control.pidFile, "utf8").trim();
  assertOk(currentPid === String(control.pid),
    `node-1 PID file changed after validation: expected ${control.pid}, got ${currentPid}`);
  validateControlledProcess(control.pid, control.wsPort, control.httpPort);
  const health = await fetchHealth(node1HttpUrl);
  assertOk(health.nodeId === "node-1", `PID control target did not report node-1 health: ${JSON.stringify(health)}`);

  process.kill(control.pid, "SIGTERM");
  await waitForAsync(async () => isProcessAlive(control.pid) ? undefined : true, {
    timeoutMs: Math.max(30_000, config.requestTimeoutMs * 6),
    intervalMs: config.pollIntervalMs,
    description: `node-1 PID ${control.pid} to exit after SIGTERM`,
  });
}

function validateControlledProcess(pid: number, wsPort: number, httpPort: number): void {
  assertOk(isProcessAlive(pid), `node-1 PID ${pid} is not running`);
  assertOk(pidOwnsListeningPort(pid, wsPort), `PID ${pid} does not own node-1 WS port ${wsPort}`);
  assertOk(pidOwnsListeningPort(pid, httpPort), `PID ${pid} does not own node-1 HTTP port ${httpPort}`);
}

function pidOwnsListeningPort(pid: number, port: number): boolean {
  try {
    const output = execFileSync("lsof", [
      "-nP", "-a", "-p", String(pid), `-iTCP:${port}`, "-sTCP:LISTEN", "-t",
    ], { encoding: "utf8" });
    return output.split(/\s+/).includes(String(pid));
  } catch {
    return false;
  }
}

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function fetchHealth(baseUrl: string): Promise<HealthPayload> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.requestTimeoutMs);
  try {
    const response = await fetch(new URL("/health/live", baseUrl), { signal: controller.signal });
    const body = await response.text();
    assertOk(response.ok, `health check failed: HTTP ${response.status}: ${body}`);
    return JSON.parse(body) as HealthPayload;
  } finally {
    clearTimeout(timeout);
  }
}

async function assertSignalPushAfter(
  user: ScenarioUser,
  cursor: number,
  roomId: string,
  action: string,
  description: string,
): Promise<void> {
  await user.ws.waitForPushAfter(cursor, (push) => {
    const data = push.data as MessagePush | undefined;
    return push.op === SCENARIO_PUSH_OP.MESSAGE &&
      data?.contentType === SCENARIO_CONTENT_TYPE.SIGNAL &&
      isSignalingContent(data.content, { roomId, action });
  }, description);
}

function readPositiveEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number(raw);
  assertOk(Number.isSafeInteger(parsed) && parsed > 0, `${name} must be a positive integer`);
  return parsed;
}

function isLoopback(hostname: string): boolean {
  return hostname === "127.0.0.1" || hostname === "localhost" || hostname === "::1";
}

function urlPort(url: URL): number {
  const fallback = url.protocol === "https:" || url.protocol === "wss:" ? 443 : 80;
  const port = url.port ? Number(url.port) : fallback;
  assertOk(Number.isSafeInteger(port) && port > 0, `invalid node-1 URL port: ${url.toString()}`);
  return port;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

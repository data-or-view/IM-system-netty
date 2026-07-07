import { assertOk } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { readNumberArg } from "../src/cli.js";
import { loadScenarioConfig } from "../src/config.js";
import { hasTextContent } from "../src/message-content.js";
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
import type { GroupInfo, MessagePush, SendMessageAck } from "../src/types.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);

const node1HttpUrl = process.env.IM_SCENARIO_NODE1_HTTP_URL ?? CLUSTER_NODE_DEFAULTS.node1.httpUrl;
const node1WsUrl = process.env.IM_SCENARIO_NODE1_WS_URL ?? CLUSTER_NODE_DEFAULTS.node1.wsUrl;
const node2HttpUrl = process.env.IM_SCENARIO_NODE2_HTTP_URL ?? CLUSTER_NODE_DEFAULTS.node2.httpUrl;
const node2WsUrl = process.env.IM_SCENARIO_NODE2_WS_URL ?? CLUSTER_NODE_DEFAULTS.node2.wsUrl;
const holdMs = readNumberArg("hold-ms", 0);

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
    members: [receiverPrimary.userId],
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

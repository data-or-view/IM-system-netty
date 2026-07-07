import { assertOk, sleep } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { loadScenarioConfig } from "../src/config.js";
import {
  GROUP_JOIN_VERIFICATION_CODE,
  SCENARIO_CONTENT_TYPE,
  SCENARIO_OP,
} from "../src/protocol.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { GroupInfo, SendMessageAck, SystemMessageInboxItem } from "../src/types.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const groupName = `scenario_disband_${suffix}`;
const users: ScenarioUser[] = [];

try {
  reporter.step("creating users for friend/group side-effect scenario");
  for (const nickname of ["Owner", "Member"]) {
    const user = new ScenarioUser({
      httpUrl: config.httpUrl,
      wsUrl: config.wsUrl,
      requestTimeoutMs: config.requestTimeoutMs,
      password: config.defaultPassword,
      nickname: `${nickname} Side Effect ${suffix}`,
    });
    await user.register();
    users.push(user);
  }
  const [owner, member] = users;
  assertOk(owner.userId && member.userId, "users were not registered");
  reporter.metric("ownerUserId", owner.userId);
  reporter.metric("memberUserId", member.userId);

  reporter.step("connecting users through websocket login");
  await Promise.all(users.map((user) => user.connectAndLogin()));

  reporter.step("creating and approving friend relation");
  await owner.http.post("/api/friend/apply", { toUserId: member.userId, reqMsg: "scenario friend" });
  await member.http.post("/api/friend/approve", { fromUserId: owner.userId, agreed: true, handleMsg: "ok" });

  reporter.step("checking friend message works before removal");
  const singleAck = await owner.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND, {
    toUserId: member.userId,
    clientMsgId: nextClientMsgId("scenario-single"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: "hello before delete" },
  });
  assertOk(singleAck.data?.conversationId, "single chat did not return conversationId before delete");

  reporter.step("removing friend and checking deleted side cannot send");
  await owner.http.post("/api/friend/remove", { friendUserId: member.userId });
  const rejectedByFriendRemoval = await captureWsError(() => member.ws.request(SCENARIO_OP.CHAT_SEND, {
    toUserId: owner.userId,
    clientMsgId: nextClientMsgId("scenario-single"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: "hello after delete" },
  }));
  assertOk(
    rejectedByFriendRemoval.includes("对方已删除你，无法发送消息"),
    `expected friend removal send error, got: ${rejectedByFriendRemoval}`,
  );

  reporter.step("creating group and checking group message works before disband");
  const group = await owner.http.post<GroupInfo>("/api/group/create", {
    groupName,
    members: [member.userId],
    needVerification: GROUP_JOIN_VERIFICATION_CODE.DIRECT,
  });
  assertOk(group.groupId, "group.create did not return groupId");
  const groupAck = await member.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND_GROUP, {
    groupId: group.groupId,
    clientMsgId: nextClientMsgId("scenario-group"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: "hello before disband" },
  });
  assertOk(groupAck.data?.conversationId, "group chat did not return conversationId before disband");

  reporter.step("disbanding group and checking member cannot send");
  await owner.http.post("/api/group/disband", { groupId: group.groupId });
  const rejectedByDisband = await captureWsError(() => member.ws.request(SCENARIO_OP.CHAT_SEND_GROUP, {
    groupId: group.groupId,
    clientMsgId: nextClientMsgId("scenario-group"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: "hello after disband" },
  }));
  assertOk(
    rejectedByDisband.includes("群聊已解散，无法发送消息"),
    `expected group disband send error, got: ${rejectedByDisband}`,
  );

  reporter.step("checking affected member receives group disband system message");
  const systemMessage = await waitForGroupDisbandSystemMessage(member, groupName);
  assertOk(systemMessage.title === "群聊已解散", `unexpected system message title: ${systemMessage.title}`);
  reporter.metric("groupId", group.groupId);
  reporter.finish();
} finally {
  for (const user of users) user.close();
}

async function captureWsError(action: () => Promise<unknown>): Promise<string> {
  try {
    await action();
  } catch (err) {
    return err instanceof Error ? err.message : String(err);
  }
  throw new Error("expected websocket request to fail");
}

async function waitForGroupDisbandSystemMessage(
  user: ScenarioUser,
  groupName: string,
): Promise<SystemMessageInboxItem> {
  const deadline = Date.now() + config.requestTimeoutMs;
  let lastMessages: SystemMessageInboxItem[] = [];
  while (Date.now() <= deadline) {
    const inbox = await user.http.get<{ messages?: SystemMessageInboxItem[] }>("/api/system/messages", {
      channelId: "group",
      limit: 20,
    });
    const messages = inbox.messages ?? [];
    lastMessages = messages;
    const found = messages.find((message) =>
      message.contentType === "group_disbanded" && message.content?.includes(groupName)
    );
    if (found) return found;
    await sleep(config.pollIntervalMs);
  }
  throw new Error(`Timed out waiting for group disband system inbox message; last=${JSON.stringify(lastMessages.slice(0, 5))}`);
}

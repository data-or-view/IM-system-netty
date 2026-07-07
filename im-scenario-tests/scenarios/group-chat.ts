import { assertOk } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { readNumberArg, readStringArg } from "../src/cli.js";
import { loadScenarioConfig } from "../src/config.js";
import { hasTextContent } from "../src/message-content.js";
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
const userCount = readNumberArg("users", 3);
const messageCount = readNumberArg("messages", 1);
const groupName = readStringArg("group", `scenario_group_${Date.now().toString(36)}`);
const suffix = Date.now().toString(36);
const users: ScenarioUser[] = [];

try {
  reporter.step(`creating ${userCount} test users`);
  for (let i = 0; i < userCount; i++) {
    const user = new ScenarioUser({
      httpUrl: config.httpUrl,
      wsUrl: config.wsUrl,
      requestTimeoutMs: config.requestTimeoutMs,
      password: config.defaultPassword,
      nickname: `Scenario ${suffix} ${i + 1}`,
    });
    await user.register();
    users.push(user);
  }
  reporter.metric("createdUsers", users.length);

  reporter.step("connecting all users through websocket login");
  await Promise.all(users.map((user) => user.connectAndLogin()));
  reporter.metric("connectedUsers", users.length);

  const owner = users[0];
  const memberIds = users.slice(1).map((user) => user.userId).filter((id): id is string => Boolean(id));
  reporter.step(`creating group with ${memberIds.length} invited members`);
  const group = await owner.http.post<GroupInfo>("/api/group/create", {
    groupName,
    members: memberIds,
    needVerification: GROUP_JOIN_VERIFICATION_CODE.DIRECT,
  });
  assertOk(group.groupId, "group.create did not return groupId");
  reporter.metric("groupId", group.groupId);

  const receivers = users.slice(1);
  const receiverCursors = receivers.map((user) => ({
    user,
    cursor: user.ws.markPushCursor(),
  }));
  const sentTexts: string[] = [];
  reporter.step(`sending ${messageCount} group messages from owner`);
  for (let i = 0; i < messageCount; i++) {
    const text = `scenario message ${i + 1}/${messageCount}`;
    sentTexts.push(text);
    const ack = await owner.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND_GROUP, {
      groupId: group.groupId,
      clientMsgId: nextClientMsgId("scenario-group"),
      _ct: SCENARIO_CONTENT_TYPE.TEXT,
      content: { text },
    });
    assertOk(ack.data?.seq !== undefined, `message ${i + 1} did not return seq`);
  }
  reporter.metric("sentMessages", messageCount);

  reporter.step("checking every online member receives every group message push");
  await Promise.all(receiverCursors.flatMap(({ user, cursor }) =>
    sentTexts.map((text) => user.ws.waitForPushAfter(cursor, (push) => {
      const data = push.data as MessagePush | undefined;
      return push.op === SCENARIO_PUSH_OP.MESSAGE &&
        data?.groupId === group.groupId &&
        data.fromUserId === owner.userId &&
        hasTextContent(data.content, text);
    }, `group message push "${text}" for ${user.userId}`))
  ));
  reporter.metric("receiverPushes", receivers.length * sentTexts.length);
  reporter.finish();
} finally {
  for (const user of users) user.close();
}

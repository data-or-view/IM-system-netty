import { assertOk } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { readNumberArg, readStringArg } from "../src/cli.js";
import { loadScenarioConfig } from "../src/config.js";
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
    needVerification: 0,
  });
  assertOk(group.groupId, "group.create did not return groupId");
  reporter.metric("groupId", group.groupId);

  reporter.step(`sending ${messageCount} group messages from owner`);
  for (let i = 0; i < messageCount; i++) {
    const ack = await owner.ws.request<SendMessageAck>("chat.send.group", {
      groupId: group.groupId,
      clientMsgId: nextClientMsgId("scenario-group"),
      _ct: "text",
      content: { text: `scenario message ${i + 1}/${messageCount}` },
    });
    assertOk(ack.data?.seq !== undefined, `message ${i + 1} did not return seq`);
  }
  reporter.metric("sentMessages", messageCount);

  reporter.step("checking every online member receives at least one group message push");
  const receivers = users.slice(1);
  await Promise.all(receivers.map((user) => user.ws.waitForPush((push) => {
    const data = push.data as MessagePush | undefined;
    return push.op === "message" && data?.groupId === group.groupId;
  }, `group message push for ${user.userId}`)));
  reporter.metric("receiverPushes", receivers.length);
  reporter.finish();
} finally {
  for (const user of users) user.close();
}

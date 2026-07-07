import { assertOk, waitForAsync } from "../src/assertions.js";
import { loadScenarioConfig } from "../src/config.js";
import { isSystemContent, parseMessageContent } from "../src/message-content.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { GroupInfo } from "../src/types.js";

interface MessageRecord {
  contentType?: number;
  content?: unknown;
  groupId?: string;
  fromUserId?: string;
}

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const owner = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Join Owner ${suffix}`,
});
const joiner = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Joiner ${suffix}`,
});

try {
  reporter.step("creating owner and joiner users");
  await owner.register();
  await joiner.register();
  await Promise.all([owner.connectAndLogin(), joiner.connectAndLogin()]);

  reporter.step("owner creates a public direct-join group");
  const group = await owner.http.post<GroupInfo>("/api/group/create", {
    groupName: `scenario_join_system_${suffix}`,
    needVerification: 0,
  });
  assertOk(group.groupId, "group.create did not return groupId");
  reporter.metric("groupId", group.groupId);

  reporter.step("joiner joins the group");
  const ownerPushCursor = owner.ws.markPushCursor();
  await joiner.http.post("/api/group/join", { groupId: group.groupId });

  reporter.step("owner receives the group system message push");
  await owner.ws.waitForPushAfter(ownerPushCursor, (push) => {
    const data = push.data as MessageRecord | undefined;
    return push.op === "message" &&
      data?.groupId === group.groupId &&
      data.contentType === 4 &&
      isSystemContent(data.content, "group_member_joined");
  }, "member joined system push");

  const systemMessage = await waitForAsync(async () => {
    const pulled = await owner.http.post<{ messages?: MessageRecord[] }>("/api/msg/pull", {
      conversationId: `group_${group.groupId}`,
      startSeq: 1,
      limit: 20,
    });
    return pulled.messages?.find((message) =>
      message.contentType === 4 &&
      message.groupId === group.groupId &&
      isSystemContent(message.content, "group_member_joined")
    );
  }, {
    timeoutMs: config.requestTimeoutMs,
    intervalMs: 200,
    description: "group member joined system history message",
  });
  assertOk(systemMessage, "history did not contain group member joined system message");
  assertOk(systemMessage.fromUserId === "im-system", `expected fromUserId=im-system, got ${systemMessage.fromUserId}`);
  const content = parseMessageContent(systemMessage.content) as { message?: string };
  assertOk(content.message?.includes(joiner.userId ?? ""), "system message missing joined user id");
  reporter.finish();
} finally {
  owner.close();
  joiner.close();
}

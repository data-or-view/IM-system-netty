import { assertOk, sleep } from "../src/assertions.js";
import { loadScenarioConfig } from "../src/config.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { GroupInfo } from "../src/types.js";

interface MessageRecord {
  contentType?: number;
  content?: string;
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
  await joiner.http.post("/api/group/join", { groupId: group.groupId });

  reporter.step("owner receives the group system message push");
  await owner.ws.waitForPush((push) => {
    const data = push.data as MessageRecord | undefined;
    return push.op === "message" && data?.groupId === group.groupId && data.contentType === 4;
  }, "member joined system push");

  // Persistence is async, so give the queue a tiny window before history pull.
  await sleep(300);
  const pulled = await owner.http.post<{ messages?: MessageRecord[] }>("/api/msg/pull", {
    conversationId: `group_${group.groupId}`,
    startSeq: 1,
    limit: 20,
  });
  const systemMessage = pulled.messages?.find((message) => message.contentType === 4 && message.groupId === group.groupId);
  assertOk(systemMessage, "history did not contain group member joined system message");
  assertOk(systemMessage.fromUserId === "im-system", `expected fromUserId=im-system, got ${systemMessage.fromUserId}`);
  assertOk(systemMessage.content?.includes("group_member_joined"), "system message missing group_member_joined type");
  assertOk(systemMessage.content?.includes(joiner.userId ?? ""), "system message missing joined user id");
  reporter.finish();
} finally {
  owner.close();
  joiner.close();
}

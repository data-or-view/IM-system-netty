import { assertOk, waitForAsync } from "../src/assertions.js";
import { loadScenarioConfig } from "../src/config.js";
import { isSystemContent, parseMessageContent } from "../src/message-content.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { GroupApplyInfo, GroupInfo, GroupMemberInfo, ScenarioMessage } from "../src/types.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const owner = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Group Apply Owner ${suffix}`,
});
const joiner = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Group Apply Joiner ${suffix}`,
});

try {
  reporter.step("creating owner and joiner users");
  await owner.register();
  await joiner.register();
  await Promise.all([owner.connectAndLogin(), joiner.connectAndLogin()]);
  assertOk(owner.userId && joiner.userId, "users were not registered");
  reporter.metric("ownerUserId", owner.userId);
  reporter.metric("joinerUserId", joiner.userId);

  reporter.step("owner creates an approval-required group");
  const group = await owner.http.post<GroupInfo>("/api/group/create", {
    groupName: `scenario_group_apply_${suffix}`,
    needVerification: 1,
  });
  assertOk(group.groupId, "group.create did not return groupId");
  reporter.metric("groupId", group.groupId);

  reporter.step("owner receives group apply push and pending apply list entry");
  const ownerCursor = owner.ws.markPushCursor();
  const reqMsg = `please approve ${suffix}`;
  const joinResult = await joiner.http.post<{ result?: string; status?: string }>("/api/group/join", {
    groupId: group.groupId,
    reqMsg,
  });
  assertOk(joinResult.result === "APPLY_CREATED" || joinResult.status === "APPLY_CREATED",
    `expected APPLY_CREATED, got ${JSON.stringify(joinResult)}`);

  await owner.ws.waitForPushAfter(ownerCursor, (push) => {
    const data = push.data as GroupApplyInfo | undefined;
    return push.op === "group.apply" &&
      data?.groupId === group.groupId &&
      data.userId === joiner.userId &&
      data.handleResult === "PENDING";
  }, "group apply created push");

  const pending = await owner.http.get<{ applies?: GroupApplyInfo[] }>("/api/group/apply/list", {
    onlyPending: true,
  });
  assertOk(
    pending.applies?.some((apply) =>
      apply.groupId === group.groupId &&
      apply.userId === joiner.userId &&
      apply.handleResult === "PENDING"),
    "owner pending group apply list does not contain joiner",
  );
  const count = await owner.http.get<{ count: number }>("/api/group/apply/unhandled/count");
  assertOk(count.count >= 1, `expected unhandled group apply count >= 1, got ${count.count}`);

  reporter.step("joiner gets approval push and becomes a group member");
  const joinerCursor = joiner.ws.markPushCursor();
  await owner.http.post("/api/group/apply/approve", {
    groupId: group.groupId,
    userId: joiner.userId,
    agreed: true,
    handleMsg: "welcome",
  });
  await joiner.ws.waitForPushAfter(joinerCursor, (push) => {
    const data = push.data as GroupApplyInfo | undefined;
    return push.op === "group.apply" &&
      data?.groupId === group.groupId &&
      data.userId === joiner.userId &&
      data.handleResult === "AGREED";
  }, "group apply approved push");

  const members = await joiner.http.get<{ members?: GroupMemberInfo[] }>("/api/group/members", {
    groupId: group.groupId,
  });
  assertOk(members.members?.some((member) => member.userId === joiner.userId),
    "joiner is not present in approved group member list");

  reporter.step("group history contains member joined system message after approval");
  const joinedSystemMessage = await waitForAsync(async () => {
    const pulled = await owner.http.post<{ messages?: ScenarioMessage[] }>("/api/msg/pull", {
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
    description: "approved join group_member_joined history message",
  });
  const content = parseMessageContent(joinedSystemMessage.content) as { message?: string };
  assertOk(content.message?.includes(joiner.userId), "approved join system message missing joiner id");

  reporter.finish();
} finally {
  owner.close();
  joiner.close();
}

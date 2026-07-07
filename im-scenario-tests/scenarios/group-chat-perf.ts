import { performance } from "node:perf_hooks";
import { assertOk, waitFor } from "../src/assertions.js";
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
const userCount = Math.max(2, readNumberArg("users", 3));
const messageCount = readNumberArg("messages", 30);
const concurrency = readNumberArg("concurrency", 1);
const groupName = readStringArg("group", `scenario_perf_${Date.now().toString(36)}`);
const suffix = Date.now().toString(36);
const users: ScenarioUser[] = [];

try {
  reporter.step(`creating ${userCount} users for group chat perf`);
  for (let i = 0; i < userCount; i++) {
    const user = new ScenarioUser({
      httpUrl: config.httpUrl,
      wsUrl: config.wsUrl,
      requestTimeoutMs: config.requestTimeoutMs,
      password: config.defaultPassword,
      nickname: `Perf ${suffix} ${i + 1}`,
    });
    await user.register();
    users.push(user);
  }
  reporter.metric("createdUsers", users.length);

  reporter.step("connecting users through websocket login");
  await Promise.all(users.map((user) => user.connectAndLogin()));

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
  const expectedTexts = Array.from({ length: messageCount }, (_, index) => `perf message ${index + 1}/${messageCount}`);
  reporter.step(`sending ${messageCount} group messages with concurrency=${concurrency}`);
  const started = performance.now();
  let nextMessage = 0;
  const workers = Array.from({ length: concurrency }, async () => {
    while (nextMessage < messageCount) {
      const current = ++nextMessage;
      const ack = await owner.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND_GROUP, {
        groupId: group.groupId,
        clientMsgId: nextClientMsgId("scenario-perf"),
        _ct: SCENARIO_CONTENT_TYPE.TEXT,
          content: { text: expectedTexts[current - 1] },
      });
      assertOk(ack.data?.seq !== undefined, `message ${current} did not return seq`);
    }
  });
  await Promise.all(workers);
  const durationMs = Math.round(performance.now() - started);
  const throughput = Math.round((messageCount / Math.max(durationMs, 1)) * 1000);

  reporter.metric("sentMessages", messageCount);
  reporter.metric("durationMs", durationMs);
  reporter.metric("throughputMsgPerSec", throughput);

  reporter.step("checking every online member receives every perf message");
  await Promise.all(receiverCursors.map(({ user, cursor }) => waitFor(() => {
    const received = new Set<string>();
    for (const push of user.ws.pushesAfter(cursor)) {
      const data = push.data as MessagePush | undefined;
      if (push.op !== SCENARIO_PUSH_OP.MESSAGE || data?.groupId !== group.groupId || data.fromUserId !== owner.userId) {
        continue;
      }
      for (const text of expectedTexts) {
        if (hasTextContent(data.content, text)) {
          received.add(text);
          break;
        }
      }
    }
    return received.size === expectedTexts.length ? received : undefined;
  }, {
    timeoutMs: config.requestTimeoutMs,
    intervalMs: 100,
    description: `all perf group message pushes for ${user.userId}`,
  })));
  reporter.metric("verifiedReceivers", receivers.length);
  reporter.finish();
} finally {
  for (const user of users) user.close();
}

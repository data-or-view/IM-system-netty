import { performance } from "node:perf_hooks";
import { assertOk } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { readNumberArg, readStringArg } from "../src/cli.js";
import { loadScenarioConfig } from "../src/config.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { GroupInfo, SendMessageAck } from "../src/types.js";

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
    needVerification: 0,
  });
  assertOk(group.groupId, "group.create did not return groupId");
  reporter.metric("groupId", group.groupId);

  reporter.step(`sending ${messageCount} group messages with concurrency=${concurrency}`);
  const started = performance.now();
  let nextMessage = 0;
  const workers = Array.from({ length: concurrency }, async () => {
    while (nextMessage < messageCount) {
      const current = ++nextMessage;
      const ack = await owner.ws.request<SendMessageAck>("chat.send.group", {
        groupId: group.groupId,
        clientMsgId: nextClientMsgId("scenario-perf"),
        _ct: "text",
        content: { text: `perf message ${current}/${messageCount}` },
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
  reporter.finish();
} finally {
  for (const user of users) user.close();
}

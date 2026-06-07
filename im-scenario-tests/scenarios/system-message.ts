import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { assertOk } from "../src/assertions.js";
import { readStringArg } from "../src/cli.js";
import { loadScenarioConfig } from "../src/config.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";

const execFileAsync = promisify(execFile);

interface SystemMessageSummary {
  messageId: string;
  channelId: string;
  title: string;
  summary?: string;
  createdAt: number;
}

interface SystemMessageInboxItem extends SystemMessageSummary {
  content?: string;
  readAt?: number;
}

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const publisher = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `System Publisher ${suffix}`,
});
const receiver = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `System Receiver ${suffix}`,
});

try {
  reporter.step("creating publisher and receiver users");
  await publisher.register();
  await receiver.register();
  assertOk(publisher.userId, "publisher userId missing");
  assertOk(receiver.userId, "receiver userId missing");
  reporter.metric("publisherUserId", publisher.userId);
  reporter.metric("receiverUserId", receiver.userId);

  reporter.step("promoting publisher to ADMIN in development database");
  await promoteUserToAdmin(publisher.userId);

  reporter.step("connecting publisher and receiver websocket sessions");
  await Promise.all([publisher.connectAndLogin(), receiver.connectAndLogin()]);

  const channelId = `scenario_${suffix}`;
  const title = `系统通知 ${suffix}`;
  const content = `这是系统通知场景测试 ${suffix}`;

  reporter.step("publishing system message to the online receiver");
  const published = await publisher.http.post<{ message?: SystemMessageSummary }>("/api/admin/system/messages/publish", {
    channelId,
    title,
    summary: "你有一条新的系统通知",
    content,
    targetUserIds: [receiver.userId],
  });
  const messageId = published.message?.messageId;
  assertOk(messageId, "publish did not return messageId");
  reporter.metric("messageId", messageId);

  reporter.step("receiver gets realtime system.message websocket push");
  const push = await receiver.ws.waitForPush((event) => {
    const data = event.data as SystemMessageSummary | undefined;
    return event.op === "system.message" && data?.messageId === messageId;
  }, "system.message push");
  const pushData = push.data as SystemMessageSummary;
  assertOk(pushData.title === title, `unexpected push title: ${pushData.title}`);

  reporter.step("receiver lists inbox and reads detail through HTTP");
  const inbox = await receiver.http.get<{ messages?: SystemMessageInboxItem[] }>("/api/system/messages", { channelId });
  const inboxItem = inbox.messages?.find((item) => item.messageId === messageId);
  assertOk(inboxItem, "receiver inbox does not contain published system message");
  assertOk(inboxItem.readAt === 0, `expected unread readAt=0, got ${inboxItem.readAt}`);

  const detail = await receiver.http.get<SystemMessageInboxItem>("/api/system/messages/detail", { messageId });
  assertOk(detail.content === content, "system message detail content mismatch");

  reporter.step("receiver unread count changes after mark read");
  const beforeRead = await receiver.http.get<{ count: number; byChannel?: Record<string, number> }>("/api/system/messages/unread-count", { channelId });
  assertOk(beforeRead.count >= 1, `expected unread count >= 1, got ${beforeRead.count}`);

  await receiver.http.post("/api/system/messages/read", { messageId });
  const afterRead = await receiver.http.get<{ count: number }>("/api/system/messages/unread-count", { channelId });
  assertOk(afterRead.count === 0, `expected unread count 0 after read, got ${afterRead.count}`);

  reporter.finish();
} finally {
  publisher.close();
  receiver.close();
}

async function promoteUserToAdmin(userId: string): Promise<void> {
  const container = readStringArg("mysql-container", process.env.IM_SCENARIO_MYSQL_CONTAINER ?? "mysql8.0-by-compose-port-3306");
  const database = readStringArg("mysql-db", process.env.IM_SCENARIO_MYSQL_DB ?? "im_system");
  const user = readStringArg("mysql-user", process.env.IM_SCENARIO_MYSQL_USER ?? "root");
  const password = readStringArg("mysql-password", process.env.IM_SCENARIO_MYSQL_PASSWORD ?? "123456");
  const sql = `UPDATE im_users SET app_manger_level = 1 WHERE user_id = '${escapeSql(userId)}'`;

  await execFileAsync("docker", [
    "exec",
    container,
    "mysql",
    `-u${user}`,
    `-p${password}`,
    database,
    "-e",
    sql,
  ]);
}

function escapeSql(value: string): string {
  return value.replaceAll("\\", "\\\\").replaceAll("'", "\\'");
}

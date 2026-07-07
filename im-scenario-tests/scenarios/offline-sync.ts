import { assertOk, waitForAsync } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { loadScenarioConfig } from "../src/config.js";
import { hasTextContent } from "../src/message-content.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { ConversationInfo, FriendInfo, ScenarioMessage, SendMessageAck } from "../src/types.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const sender = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Offline Sender ${suffix}`,
});
const receiver = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Offline Receiver ${suffix}`,
});

try {
  reporter.step("creating sender and receiver users");
  await sender.register();
  await receiver.register();
  assertOk(sender.userId && receiver.userId, "users were not registered");
  reporter.metric("senderUserId", sender.userId);
  reporter.metric("receiverUserId", receiver.userId);

  reporter.step("connecting users and creating friend relation");
  await Promise.all([sender.connectAndLogin(), receiver.connectAndLogin()]);
  await sender.http.post("/api/friend/apply", { toUserId: receiver.userId, reqMsg: "offline sync friend" });
  await receiver.http.post("/api/friend/approve", { fromUserId: sender.userId, agreed: true, handleMsg: "ok" });

  const friends = await receiver.http.get<{ friends?: FriendInfo[] }>("/api/friend/list");
  assertOk(
    friends.friends?.some((friend) => friend.friendUserId === sender.userId),
    "receiver friend list does not contain sender",
  );

  reporter.step("receiver goes offline before sender sends a single chat message");
  receiver.close();
  const text = `offline sync message ${suffix}`;
  const ack = await sender.ws.request<SendMessageAck>("chat.send", {
    toUserId: receiver.userId,
    clientMsgId: nextClientMsgId("scenario-offline"),
    _ct: "text",
    content: { text },
  });
  assertOk(ack.data?.conversationId, "single chat did not return conversationId");
  assertOk(ack.data.seq > 0, `single chat did not return positive seq: ${ack.data.seq}`);
  reporter.metric("conversationId", ack.data.conversationId);

  reporter.step("receiver reconnects and pulls missed messages through incremental sync");
  await receiver.connectAndLogin();
  const syncedMessage = await waitForAsync(async () => {
    const result = await receiver.http.post<{
      syncs?: Array<{ conversationId: string; messages?: ScenarioMessage[]; maxSeq?: number }>;
    }>("/api/msg/sync", {
      seqs: { [ack.data!.conversationId]: 0 },
      limit: 20,
    });
    const sync = result.syncs?.find((item) => item.conversationId === ack.data?.conversationId);
    return sync?.messages?.find((message) =>
      message.fromUserId === sender.userId &&
      message.toUserId === receiver.userId &&
      hasTextContent(message.content, text)
    );
  }, {
    timeoutMs: config.requestTimeoutMs,
    intervalMs: 200,
    description: "offline single chat message in msg.sync",
  });
  assertOk(syncedMessage, "sync did not return the offline message");

  reporter.step("receiver conversation list exposes the single chat");
  const conversation = await waitForAsync(async () => {
    const list = await receiver.http.get<{ conversations?: ConversationInfo[] }>("/api/conversation/list");
    return list.conversations?.find((item) => item.conversationId === ack.data?.conversationId);
  }, {
    timeoutMs: config.requestTimeoutMs,
    intervalMs: 200,
    description: "receiver single chat conversation after offline message",
  });
  assertOk(conversation.conversationId === ack.data.conversationId, "conversation id mismatch after offline sync");

  reporter.finish();
} finally {
  sender.close();
  receiver.close();
}

import { assertOk, waitForAsync } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { loadScenarioConfig } from "../src/config.js";
import { hasTextContent } from "../src/message-content.js";
import { SCENARIO_CONTENT_TYPE, SCENARIO_OP } from "../src/protocol.js";
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
const secondSender = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Offline Sender 2 ${suffix}`,
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
  await secondSender.register();
  await receiver.register();
  assertOk(sender.userId && secondSender.userId && receiver.userId, "users were not registered");
  reporter.metric("senderUserId", sender.userId);
  reporter.metric("secondSenderUserId", secondSender.userId);
  reporter.metric("receiverUserId", receiver.userId);

  reporter.step("connecting users and creating friend relations");
  await Promise.all([sender.connectAndLogin(), secondSender.connectAndLogin(), receiver.connectAndLogin()]);
  await sender.http.post("/api/friend/apply", { toUserId: receiver.userId, reqMsg: "offline sync friend" });
  await receiver.http.post("/api/friend/approve", { fromUserId: sender.userId, agreed: true, handleMsg: "ok" });
  await secondSender.http.post("/api/friend/apply", { toUserId: receiver.userId, reqMsg: "offline sync friend 2" });
  await receiver.http.post("/api/friend/approve", { fromUserId: secondSender.userId, agreed: true, handleMsg: "ok" });

  const friends = await receiver.http.get<{ friends?: FriendInfo[] }>("/api/friend/list");
  assertOk(
    friends.friends?.some((friend) => friend.friendUserId === sender.userId),
    "receiver friend list does not contain sender",
  );
  assertOk(
    friends.friends?.some((friend) => friend.friendUserId === secondSender.userId),
    "receiver friend list does not contain second sender",
  );

  reporter.step("receiver goes offline before senders write multiple conversations");
  receiver.close();
  const text = `offline sync message ${suffix}`;
  const secondText = `offline sync second conversation ${suffix}`;
  const followupText = `offline sync followup ${suffix}`;
  const ack = await sender.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND, {
    toUserId: receiver.userId,
    clientMsgId: nextClientMsgId("scenario-offline"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text },
  });
  const followupAck = await sender.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND, {
    toUserId: receiver.userId,
    clientMsgId: nextClientMsgId("scenario-offline"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: followupText },
  });
  const secondAck = await secondSender.ws.request<SendMessageAck>(SCENARIO_OP.CHAT_SEND, {
    toUserId: receiver.userId,
    clientMsgId: nextClientMsgId("scenario-offline-2"),
    _ct: SCENARIO_CONTENT_TYPE.TEXT,
    content: { text: secondText },
  });
  assertOk(ack.data?.conversationId, "single chat did not return conversationId");
  assertOk(ack.data.seq > 0, `single chat did not return positive seq: ${ack.data.seq}`);
  assertOk(followupAck.data?.conversationId === ack.data.conversationId, "followup message conversation mismatch");
  assertOk(secondAck.data?.conversationId, "second single chat did not return conversationId");
  reporter.metric("conversationId", ack.data.conversationId);
  reporter.metric("secondConversationId", secondAck.data.conversationId);

  reporter.step("receiver reconnects and pulls missed messages through multi-conversation incremental sync");
  await receiver.connectAndLogin();
  const syncedMessages = await waitForAsync(async () => {
    const result = await receiver.http.post<{
      syncs?: Array<{ conversationId: string; messages?: ScenarioMessage[]; maxSeq?: number }>;
    }>("/api/msg/sync", {
      seqs: {
        [ack.data!.conversationId]: 0,
        [secondAck.data!.conversationId]: 0,
      },
      limit: 20,
    });
    const sync = result.syncs?.find((item) => item.conversationId === ack.data?.conversationId);
    const secondSync = result.syncs?.find((item) => item.conversationId === secondAck.data?.conversationId);
    const first = sync?.messages?.find((message) =>
      message.fromUserId === sender.userId &&
      message.toUserId === receiver.userId &&
      hasTextContent(message.content, text)
    );
    const followup = sync?.messages?.find((message) =>
      message.fromUserId === sender.userId &&
      message.toUserId === receiver.userId &&
      hasTextContent(message.content, followupText)
    );
    const second = secondSync?.messages?.find((message) =>
      message.fromUserId === secondSender.userId &&
      message.toUserId === receiver.userId &&
      hasTextContent(message.content, secondText)
    );
    return first && followup && second ? [first, followup, second] : undefined;
  }, {
    timeoutMs: config.requestTimeoutMs,
    intervalMs: config.pollIntervalMs,
    description: "offline multi-conversation messages in msg.sync",
  });
  assertOk(syncedMessages.length === 3, "sync did not return all offline messages");

  reporter.step("receiver conversation list exposes both offline chats");
  const conversations = await waitForAsync(async () => {
    const list = await receiver.http.get<{ conversations?: ConversationInfo[] }>("/api/conversation/list");
    const first = list.conversations?.find((item) => item.conversationId === ack.data?.conversationId);
    const second = list.conversations?.find((item) => item.conversationId === secondAck.data?.conversationId);
    return first && second ? [first, second] : undefined;
  }, {
    timeoutMs: config.requestTimeoutMs,
    intervalMs: config.pollIntervalMs,
    description: "receiver single chat conversations after offline messages",
  });
  assertOk(conversations[0].conversationId === ack.data.conversationId, "conversation id mismatch after offline sync");
  assertOk(conversations[1].conversationId === secondAck.data.conversationId, "second conversation id mismatch after offline sync");

  reporter.finish();
} finally {
  sender.close();
  secondSender.close();
  receiver.close();
}

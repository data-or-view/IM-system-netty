import { assertOk, waitForAsync } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { loadScenarioConfig } from "../src/config.js";
import { hasTextContent } from "../src/message-content.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { FriendInfo, ScenarioMessage, SendMessageAck } from "../src/types.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const sender = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Idem Sender ${suffix}`,
});
const receiver = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Idem Receiver ${suffix}`,
});
const secondReceiver = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Idem Second Receiver ${suffix}`,
});

try {
  reporter.step("creating idempotency test users");
  await sender.register();
  await receiver.register();
  await secondReceiver.register();
  assertOk(sender.userId && receiver.userId && secondReceiver.userId, "users were not registered");
  reporter.metric("senderUserId", sender.userId);
  reporter.metric("receiverUserId", receiver.userId);
  reporter.metric("secondReceiverUserId", secondReceiver.userId);

  reporter.step("connecting users and creating friend relation");
  await Promise.all([sender.connectAndLogin(), receiver.connectAndLogin(), secondReceiver.connectAndLogin()]);
  await sender.http.post("/api/friend/apply", { toUserId: receiver.userId, reqMsg: "idempotency friend" });
  await receiver.http.post("/api/friend/approve", { fromUserId: sender.userId, agreed: true, handleMsg: "ok" });
  await sender.http.post("/api/friend/apply", { toUserId: secondReceiver.userId, reqMsg: "idempotency friend 2" });
  await secondReceiver.http.post("/api/friend/approve", { fromUserId: sender.userId, agreed: true, handleMsg: "ok" });
  const friends = await receiver.http.get<{ friends?: FriendInfo[] }>("/api/friend/list");
  assertOk(
    friends.friends?.some((friend) => friend.friendUserId === sender.userId),
    "receiver friend list does not contain sender",
  );

  const text = `idempotent message ${suffix}`;
  const clientMsgId = nextClientMsgId("scenario-idem");
  reporter.step("sending the same clientMsgId twice");
  const first = await send(clientMsgId, text);
  const second = await send(clientMsgId, text);

  assertOk(first.data?.conversationId, "first send did not return conversationId");
  assertOk(first.data.seq > 0, `first send did not return positive seq: ${first.data.seq}`);
  assertOk(second.data?.conversationId === first.data.conversationId, "duplicate send returned a different conversation");
  assertOk(second.data.seq === first.data.seq, "duplicate send returned a different sequence");
  assertOk(second.data.messageId === first.data.messageId, "duplicate send returned a different message id");
  reporter.metric("conversationId", first.data.conversationId);
  reporter.metric("messageSeq", first.data.seq);

  reporter.step("verifying duplicate send produced one persisted message");
  const messages = await waitForAsync(async () => {
    const result = await receiver.http.post<{
      syncs?: Array<{ conversationId: string; messages?: ScenarioMessage[]; maxSeq?: number }>;
    }>("/api/msg/sync", {
      seqs: { [first.data!.conversationId]: 0 },
      limit: 20,
    });
    const sync = result.syncs?.find((item) => item.conversationId === first.data?.conversationId);
    const matches = sync?.messages?.filter((message) =>
      message.fromUserId === sender.userId &&
      message.toUserId === receiver.userId &&
      message.messageId === clientMsgId &&
      hasTextContent(message.content, text)
    ) ?? [];
    return matches.length > 0 ? matches : undefined;
  }, {
    timeoutMs: config.requestTimeoutMs,
    intervalMs: 200,
    description: "idempotent message in msg.sync",
  });
  assertOk(messages.length === 1, `expected one persisted idempotent message, got ${messages.length}`);

  reporter.step("verifying same clientMsgId can be reused in another conversation");
  const crossConversationClientMsgId = nextClientMsgId("scenario-idem-cross");
  const secondText = `cross conversation idempotent message ${suffix}`;
  const crossFirst = await sendTo(receiver.userId, crossConversationClientMsgId, `${secondText} one`);
  const crossSecond = await sendTo(secondReceiver.userId, crossConversationClientMsgId, `${secondText} two`);
  assertOk(
    crossFirst.data?.conversationId && crossSecond.data?.conversationId,
    "cross-conversation sends did not return conversation ids",
  );
  assertOk(
    crossFirst.data.conversationId !== crossSecond.data.conversationId,
    "same clientMsgId reuse did not target distinct conversations",
  );
  const firstConversationMessages = await waitForMessage(
    receiver,
    crossFirst.data.conversationId,
    receiver.userId,
    crossConversationClientMsgId,
    `${secondText} one`,
  );
  const secondConversationMessages = await waitForMessage(
    secondReceiver,
    crossSecond.data.conversationId,
    secondReceiver.userId,
    crossConversationClientMsgId,
    `${secondText} two`,
  );
  assertOk(firstConversationMessages.length === 1, "first cross-conversation message was not persisted exactly once");
  assertOk(secondConversationMessages.length === 1, "second cross-conversation message was not persisted exactly once");

  reporter.finish();
} finally {
  sender.close();
  receiver.close();
  secondReceiver.close();
}

function send(clientMsgId: string, text: string) {
  return sendTo(receiver.userId!, clientMsgId, text);
}

function sendTo(toUserId: string, clientMsgId: string, text: string) {
  return sender.ws.request<SendMessageAck>("chat.send", {
    toUserId,
    clientMsgId,
    _ct: "text",
    content: { text },
  });
}

function waitForMessage(
  user: ScenarioUser,
  conversationId: string,
  toUserId: string,
  clientMsgId: string,
  text: string,
) {
  return waitForAsync(async () => {
    const result = await user.http.post<{
      syncs?: Array<{ conversationId: string; messages?: ScenarioMessage[]; maxSeq?: number }>;
    }>("/api/msg/sync", {
      seqs: { [conversationId]: 0 },
      limit: 20,
    });
    const sync = result.syncs?.find((item) => item.conversationId === conversationId);
    const matches = sync?.messages?.filter((message) =>
      message.fromUserId === sender.userId &&
      message.toUserId === toUserId &&
      message.messageId === clientMsgId &&
      hasTextContent(message.content, text)
    ) ?? [];
    return matches.length > 0 ? matches : undefined;
  }, {
    timeoutMs: config.requestTimeoutMs,
    intervalMs: 200,
    description: `message ${clientMsgId} in ${conversationId}`,
  });
}

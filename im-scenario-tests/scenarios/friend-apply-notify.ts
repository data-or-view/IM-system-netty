import { assertOk } from "../src/assertions.js";
import { loadScenarioConfig } from "../src/config.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { FriendApplyInfo, FriendInfo } from "../src/types.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const suffix = Date.now().toString(36);
const applicant = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Friend Applicant ${suffix}`,
});
const receiver = new ScenarioUser({
  httpUrl: config.httpUrl,
  wsUrl: config.wsUrl,
  requestTimeoutMs: config.requestTimeoutMs,
  password: config.defaultPassword,
  nickname: `Friend Receiver ${suffix}`,
});

try {
  reporter.step("creating users for friend apply notification scenario");
  await applicant.register();
  await receiver.register();
  assertOk(applicant.userId && receiver.userId, "users were not registered");
  reporter.metric("applicantUserId", applicant.userId);
  reporter.metric("receiverUserId", receiver.userId);

  reporter.step("connecting both websocket sessions");
  await Promise.all([applicant.connectAndLogin(), receiver.connectAndLogin()]);

  reporter.step("receiver gets realtime friend apply push and pending list entry");
  const receiverCursor = receiver.ws.markPushCursor();
  const reqMsg = `friend request ${suffix}`;
  await applicant.http.post("/api/friend/apply", { toUserId: receiver.userId, reqMsg });
  const createdPush = await receiver.ws.waitForPushAfter(receiverCursor, (push) => {
    const data = push.data as FriendApplyInfo | undefined;
    return push.op === "friend.apply" &&
      data?.fromUserId === applicant.userId &&
      data?.toUserId === receiver.userId &&
      data?.handleResult === "PENDING";
  }, "friend apply created push");
  assertOk((createdPush.data as FriendApplyInfo).reqMsg === reqMsg, "friend apply push reqMsg mismatch");

  const pending = await receiver.http.get<{ applies?: FriendApplyInfo[] }>("/api/friend/apply/received", {
    onlyPending: true,
  });
  assertOk(
    pending.applies?.some((apply) =>
      apply.fromUserId === applicant.userId &&
      apply.toUserId === receiver.userId &&
      apply.handleResult === "PENDING"),
    "receiver pending friend apply list does not contain applicant",
  );

  const count = await receiver.http.get<{ count: number }>("/api/friend/apply/unhandled/count");
  assertOk(count.count >= 1, `expected unhandled friend apply count >= 1, got ${count.count}`);

  reporter.step("applicant gets approval push and both users see each other in friend list");
  const applicantCursor = applicant.ws.markPushCursor();
  await receiver.http.post("/api/friend/approve", { fromUserId: applicant.userId, agreed: true, handleMsg: "ok" });
  await applicant.ws.waitForPushAfter(applicantCursor, (push) => {
    const data = push.data as FriendApplyInfo | undefined;
    return push.op === "friend.apply" &&
      data?.fromUserId === applicant.userId &&
      data?.toUserId === receiver.userId &&
      data?.handleResult === "AGREED";
  }, "friend apply approved push");

  const [applicantFriends, receiverFriends] = await Promise.all([
    applicant.http.get<{ friends?: FriendInfo[] }>("/api/friend/list"),
    receiver.http.get<{ friends?: FriendInfo[] }>("/api/friend/list"),
  ]);
  assertOk(applicantFriends.friends?.some((friend) => friend.friendUserId === receiver.userId),
    "applicant friend list does not contain receiver");
  assertOk(receiverFriends.friends?.some((friend) => friend.friendUserId === applicant.userId),
    "receiver friend list does not contain applicant");

  reporter.finish();
} finally {
  applicant.close();
  receiver.close();
}

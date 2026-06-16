import { assertOk } from "../src/assertions.js";
import { nextClientMsgId } from "../src/client-msg-id.js";
import { readNumberArg, readStringArg } from "../src/cli.js";
import { loadScenarioConfig } from "../src/config.js";
import { ScenarioReporter } from "../src/reporter.js";
import { ScenarioUser } from "../src/scenario-user.js";
import type { GroupCallJoinResult, GroupCallSession, GroupInfo, MessagePush } from "../src/types.js";

const config = loadScenarioConfig();
const reporter = new ScenarioReporter();
const userCount = Math.max(2, readNumberArg("users", 3));
const callType = readStringArg("type", "video");
const suffix = Date.now().toString(36);
const users: ScenarioUser[] = [];

try {
  reporter.step(`creating ${userCount} users for group call`);
  for (let i = 0; i < userCount; i++) {
    const user = new ScenarioUser({
      httpUrl: config.httpUrl,
      wsUrl: config.wsUrl,
      requestTimeoutMs: config.requestTimeoutMs,
      password: config.defaultPassword,
      nickname: `Group Call ${suffix} ${i + 1}`,
    });
    await user.register();
    users.push(user);
  }
  reporter.metric("createdUsers", users.length);

  reporter.step("connecting all users through websocket login");
  await Promise.all(users.map((user) => user.connectAndLogin()));
  reporter.metric("connectedUsers", users.length);

  const owner = users[0];
  const memberIds = users.slice(1).map((user) => user.userId).filter((id): id is string => Boolean(id));
  reporter.step("creating group for call scenario");
  const group = await owner.http.post<GroupInfo>("/api/group/create", {
    groupName: `scenario_group_call_${suffix}`,
    members: memberIds,
    needVerification: 0,
  });
  assertOk(group.groupId, "group.create did not return groupId");
  reporter.metric("groupId", group.groupId);

  reporter.step(`starting ${callType} group call`);
  const started = await owner.http.post<GroupCallSession>("/api/group/call/start", {
    groupId: group.groupId,
    callType,
    clientMsgId: nextClientMsgId("scenario-call"),
  });
  assertOk(started.active, "group.call.start did not return active session");
  assertOk(started.roomId, "group.call.start did not return roomId");
  assertOk(started.participantCount === 1, `expected participantCount=1 after start, got ${started.participantCount}`);
  reporter.metric("roomId", started.roomId);

  reporter.step("checking online members receive group call signal push");
  await Promise.all(users.slice(1).map((user) => user.ws.waitForPush((push) => {
    const data = push.data as MessagePush | undefined;
    return push.op === "message" && data?.groupId === group.groupId;
  }, `group call signal for ${user.userId}`)));
  reporter.metric("signalReceivers", users.length - 1);

  reporter.step("checking members can discover active call and join");
  const joiners = users.slice(1);
  for (const user of joiners) {
    const active = await user.http.get<GroupCallSession>("/api/group/call/active", { groupId: group.groupId });
    assertOk(active.active, `active call not visible for ${user.userId}`);
    assertOk(active.roomId === started.roomId, `active room mismatch for ${user.userId}`);

    const joined = await user.http.post<GroupCallJoinResult>("/api/group/call/join", { groupId: group.groupId });
    assertOk(joined.token, `join did not return token for ${user.userId}`);
    assertOk(joined.roomId === started.roomId, `join room mismatch for ${user.userId}`);
  }
  reporter.metric("joinedMembers", joiners.length);

  reporter.step("checking participant count after joins");
  const afterJoin = await owner.http.get<GroupCallSession>("/api/group/call/active", { groupId: group.groupId });
  assertOk(afterJoin.participantCount === users.length, `expected participantCount=${users.length}, got ${afterJoin.participantCount}`);

  reporter.step("one member leaves and initiator ends the call");
  const left = await users[1].http.post<GroupCallSession>("/api/group/call/leave", { groupId: group.groupId });
  assertOk(left.participantCount === users.length - 1, `expected participantCount=${users.length - 1} after leave, got ${left.participantCount}`);

  const ended = await owner.http.post<GroupCallSession>("/api/group/call/end", {
    groupId: group.groupId,
    clientMsgId: nextClientMsgId("scenario-call"),
  });
  assertOk(ended.active === false && ended.ended === true, "group.call.end did not end active session");

  const finalActive = await users[1].http.get<GroupCallSession>("/api/group/call/active", { groupId: group.groupId });
  assertOk(finalActive.active === false, "group.call.active should be inactive after end");
  reporter.finish();
} finally {
  for (const user of users) user.close();
}

import test from "node:test";
import assert from "node:assert/strict";
import { waitForAsync } from "../src/assertions.js";
import {
  hasTextContent,
  isSignalingContent,
  isSystemContent,
  parseMessageContent,
} from "../src/message-content.js";

test("parseMessageContent parses JSON message content and matches system type", () => {
  const parsed = parseMessageContent({
    contentType: 4,
    content: "{\"systemType\":\"group_member_joined\",\"message\":\"user joined\"}",
  });

  assert.deepEqual(parsed, {
    systemType: "group_member_joined",
    message: "user joined",
  });
  assert.equal(isSystemContent(parsed, "group_member_joined"), true);
  assert.equal(isSystemContent(parsed, "group_created"), false);
});

test("message content helpers match text and signaling payloads structurally", () => {
  assert.equal(hasTextContent({ text: "hello from scenario" }, "hello from scenario"), true);
  assert.equal(hasTextContent("{\"text\":\"hello from scenario\"}", "hello from scenario"), true);
  assert.equal(isSignalingContent({ roomId: "room-1", action: "INVITE" }, "room-1"), true);
  assert.equal(isSignalingContent({ roomId: "room-1", action: "CALLING", callType: "video" }, {
    roomId: "room-1",
    action: "CALLING",
    callType: "video",
  }), true);
  assert.equal(isSignalingContent({ roomId: "room-1", action: "CALLING", callType: "voice" }, {
    roomId: "room-1",
    action: "CALLING",
    callType: "video",
  }), false);
  assert.equal(isSignalingContent({ _room: "room-1", _act: 1 }, "room-1"), true);
  assert.equal(isSignalingContent({ _room: "room-2", _act: 1 }, "room-1"), false);
});

test("waitForAsync retries asynchronous probes until a value is available", async () => {
  let attempts = 0;

  const value = await waitForAsync(async () => {
    attempts += 1;
    return attempts >= 3 ? "ready" : undefined;
  }, {
    timeoutMs: 500,
    intervalMs: 1,
    description: "async value",
  });

  assert.equal(value, "ready");
  assert.equal(attempts, 3);
});

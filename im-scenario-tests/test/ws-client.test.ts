import test from "node:test";
import assert from "node:assert/strict";
import { WebSocketServer } from "ws";
import { ScenarioWsClient } from "../src/ws-client.js";

test("ScenarioWsClient can wait for pushes after a cursor", async () => {
  const server = new WebSocketServer({ port: 0 });
  const port = (server.address() as { port: number }).port;
  server.on("connection", (socket) => {
    socket.send(JSON.stringify({ op: "message", data: { text: "old" } }));
    socket.on("message", (raw) => {
      const frame = JSON.parse(raw.toString()) as { seq: number; op: string };
      socket.send(JSON.stringify({ op: frame.op, seq: frame.seq, code: 0, data: { ok: true } }));
      socket.send(JSON.stringify({ op: "message", data: { text: "new" } }));
    });
  });

  const client = new ScenarioWsClient(`ws://127.0.0.1:${port}`, { requestTimeoutMs: 500 });
  try {
    await client.connect();
    await client.waitForPush((push) => (push.data as { text?: string })?.text === "old", "old push");
    const cursor = client.markPushCursor();

    await client.request("trigger");
    const push = await client.waitForPushAfter(
      cursor,
      (event) => (event.data as { text?: string })?.text === "new",
      "new push",
    );

    assert.equal((push.data as { text?: string }).text, "new");
  } finally {
    client.close();
    await new Promise<void>((resolve, reject) => server.close((err) => (err ? reject(err) : resolve())));
  }
});

test("ScenarioWsClient timeout diagnostics include pushes received while waiting", async () => {
  const server = new WebSocketServer({ port: 0 });
  const port = (server.address() as { port: number }).port;
  server.on("connection", (socket) => {
    setTimeout(() => {
      socket.send(JSON.stringify({ op: "friend.apply", data: { fromUserId: "u1", toUserId: "u2" } }));
    }, 20);
  });

  const client = new ScenarioWsClient(`ws://127.0.0.1:${port}`, { requestTimeoutMs: 120 });
  try {
    await client.connect();
    await assert.rejects(
      client.waitForPushAfter(0, (push) => push.op === "missing.push", "missing push"),
      (err: unknown) => {
        assert.match(String(err), /missing push/);
        assert.match(String(err), /friend\.apply/);
        assert.match(String(err), /fromUserId/);
        return true;
      },
    );
  } finally {
    client.close();
    await new Promise<void>((resolve, reject) => server.close((err) => (err ? reject(err) : resolve())));
  }
});

import test from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { once } from "node:events";
import { ScenarioHttpClient, ScenarioHttpError } from "../src/http-client.js";

test("ScenarioHttpClient preserves a non-2xx API error envelope", async () => {
  const { server, baseUrl } = await startServer(404, {
    code: 404,
    msg: "resource missing",
    data: null,
    detail: "uploaded object not found",
    requestId: "request-404",
  });
  const client = new ScenarioHttpClient({ baseUrl, requestTimeoutMs: 500 });

  try {
    await assert.rejects(client.post("/api/file/upload/complete", { fileId: "missing" }),
      (error: unknown) => {
        assert.ok(error instanceof ScenarioHttpError);
        assert.equal(error.httpStatus, 404);
        assert.equal(error.code, 404);
        assert.equal(error.msg, "resource missing");
        assert.equal(error.detail, "uploaded object not found");
        assert.equal(error.requestId, "request-404");
        assert.equal(error.path, "/api/file/upload/complete");
        return true;
      });
  } finally {
    await closeServer(server);
  }
});

test("ScenarioHttpClient exposes an HTTP 200 business error as ScenarioHttpError", async () => {
  const { server, baseUrl } = await startServer(200, {
    code: 409,
    msg: "request conflict",
    data: null,
    detail: "upload already completed",
  });
  const client = new ScenarioHttpClient({ baseUrl, requestTimeoutMs: 500 });

  try {
    await assert.rejects(client.post("/api/file/upload/complete", { fileId: "done" }),
      (error: unknown) => {
        assert.ok(error instanceof ScenarioHttpError);
        assert.equal(error.httpStatus, 200);
        assert.equal(error.code, 409);
        assert.equal(error.detail, "upload already completed");
        return true;
      });
  } finally {
    await closeServer(server);
  }
});

async function startServer(status: number, body: Record<string, unknown>): Promise<{ server: Server; baseUrl: string }> {
  const server = createServer((_request, response) => {
    response.writeHead(status, { "content-type": "application/json" });
    response.end(JSON.stringify(body));
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address === "object", "test HTTP server must bind a TCP port");
  return { server, baseUrl: `http://127.0.0.1:${address.port}` };
}

async function closeServer(server: Server): Promise<void> {
  await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

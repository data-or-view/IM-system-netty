import WebSocket from "ws";
import { waitFor } from "./assertions.js";

export interface WsResponse<T = unknown> {
  op: string;
  seq: number;
  code: number;
  data?: T;
  msg?: string;
  detail?: string;
}

export interface WsPush<T = unknown> {
  op: string;
  data?: T;
  [key: string]: unknown;
}

export class ScenarioWsClient {
  private ws?: WebSocket;
  private seq = 0;
  private readonly pending = new Map<number, { resolve: (value: WsResponse) => void; reject: (err: Error) => void; timer: NodeJS.Timeout }>();
  private readonly pushes: WsPush[] = [];
  private readonly malformedFrames: string[] = [];

  constructor(
    private readonly wsUrl: string,
    private readonly options: { requestTimeoutMs: number; getToken?: () => string | undefined },
  ) {}

  async connect(): Promise<void> {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) return;
    this.ws = new WebSocket(this.wsUrl);
    this.ws.on("message", (data) => this.handleMessage(data.toString()));
    this.ws.on("close", (code, reason) => {
      this.rejectPending(new Error(`WebSocket closed: code=${code} reason=${reason.toString() || "none"}`));
    });
    this.ws.on("error", (err) => {
      this.rejectPending(err instanceof Error ? err : new Error(String(err)));
    });
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`WebSocket connect timeout: ${this.wsUrl}`)), this.options.requestTimeoutMs);
      this.ws?.once("open", () => { clearTimeout(timer); resolve(); });
      this.ws?.once("error", (err) => { clearTimeout(timer); reject(err); });
    });
  }

  async request<T = unknown>(op: string, params: Record<string, unknown> = {}): Promise<WsResponse<T>> {
    const ws = this.requireOpen();
    const seq = ++this.seq;
    const token = this.options.getToken?.();
    const frame = {
      op,
      seq,
      _requestId: `scenario-ws-${Date.now().toString(36)}-${seq}`,
      ...(token ? { Authorization: token } : {}),
      ...params,
    };
    const promise = new Promise<WsResponse>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(seq);
        reject(new Error(`WebSocket request timeout: ${op} seq=${seq}`));
      }, this.options.requestTimeoutMs);
      this.pending.set(seq, { resolve, reject, timer });
    });
    ws.send(JSON.stringify(frame));
    const response = await promise;
    if (response.code !== 0) {
      const detail = response.detail ?? response.msg ?? "unknown";
      throw new Error(`WS ${op} failed: code=${response.code} detail=${detail}`);
    }
    return response as WsResponse<T>;
  }

  async waitForPush(predicate: (push: WsPush) => boolean, description: string): Promise<WsPush> {
    return this.waitForPushAfter(0, predicate, description);
  }

  markPushCursor(): number {
    return this.pushes.length;
  }

  pushesAfter(cursor: number): WsPush[] {
    return this.pushes.slice(cursor);
  }

  async waitForPushAfter(
    cursor: number,
    predicate: (push: WsPush) => boolean,
    description: string,
  ): Promise<WsPush> {
    return waitFor(() => this.pushes.slice(cursor).find(predicate), {
      timeoutMs: this.options.requestTimeoutMs,
      description,
      onTimeout: () => `recentPushes=${this.describeRecentPushes()}`,
    });
  }

  close(): void {
    for (const [seq, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new Error(`WebSocket closed before response seq=${seq}`));
    }
    this.pending.clear();
    this.ws?.close();
    this.ws = undefined;
  }

  private requireOpen(): WebSocket {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error("WebSocket is not connected");
    }
    return this.ws;
  }

  private handleMessage(raw: string): void {
    let parsed: WsResponse | WsPush;
    try {
      parsed = JSON.parse(raw) as WsResponse | WsPush;
    } catch {
      this.malformedFrames.push(raw);
      if (this.malformedFrames.length > 5) this.malformedFrames.shift();
      return;
    }
    if (typeof (parsed as WsResponse).seq === "number") {
      const response = parsed as WsResponse;
      const pending = this.pending.get(response.seq);
      if (pending) {
        clearTimeout(pending.timer);
        this.pending.delete(response.seq);
        pending.resolve(response);
      }
      return;
    }
    this.pushes.push(parsed as WsPush);
  }

  private rejectPending(error: Error): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }

  private describeRecentPushes(limit = 5): string {
    const recent = this.pushes.slice(-limit).map((push) => ({
      op: push.op,
      data: summarize(push.data),
    }));
    const malformed = this.malformedFrames.length > 0
      ? ` malformed=${JSON.stringify(this.malformedFrames.slice(-2))}`
      : "";
    return `${JSON.stringify(recent)}${malformed}`;
  }
}

function summarize(value: unknown): unknown {
  if (typeof value !== "object" || value === null) return value;
  const record = value as Record<string, unknown>;
  return {
    messageId: record.messageId ?? record._mid,
    conversationId: record.conversationId,
    fromUserId: record.fromUserId,
    toUserId: record.toUserId,
    groupId: record.groupId,
    contentType: record.contentType,
    content: record.content,
  };
}

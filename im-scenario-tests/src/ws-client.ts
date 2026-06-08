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

  constructor(
    private readonly wsUrl: string,
    private readonly options: { requestTimeoutMs: number; getToken?: () => string | undefined },
  ) {}

  async connect(): Promise<void> {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) return;
    this.ws = new WebSocket(this.wsUrl);
    this.ws.on("message", (data) => this.handleMessage(data.toString()));
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
    return waitFor(() => this.pushes.find(predicate), {
      timeoutMs: this.options.requestTimeoutMs,
      description,
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
}

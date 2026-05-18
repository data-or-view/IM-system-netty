import { type WSRequest, type WSResponse, IMError, IMTimeoutError } from "../types.js";

/**
 * 请求管理器 —— 通过 seq 关联请求和响应，提供 Promise API。
 *
 * 核心机制：
 * - 每次发送请求时分配递增的 seq
 * - 将 Promise resolve/reject 存入 pending map
 * - 收到响应时通过 seq 查找对应的 pending 并 resolve
 * - 超时自动 reject
 */
export class RequestManager {
  private seq = 0;
  private pending = new Map<
    number,
    { resolve: (resp: WSResponse) => void; reject: (err: IMError) => void; timer: ReturnType<typeof setTimeout> }
  >();
  private requestTimeout: number;

  constructor(requestTimeout = 30000) {
    this.requestTimeout = requestTimeout;
  }

  /** 创建一个 WS 请求帧，返回 Promise */
  createRequest(op: string, params: Record<string, unknown> = {}): { frame: WSRequest; promise: Promise<WSResponse> } {
    const seq = ++this.seq;
    const frame: WSRequest = { op, seq, ...params };

    const promise = new Promise<WSResponse>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(seq);
        reject(new IMTimeoutError());
      }, this.requestTimeout);

      this.pending.set(seq, { resolve, reject, timer });
    });

    return { frame, promise };
  }

  /** 处理收到的响应帧 */
  resolveResponse(resp: WSResponse): boolean {
    const pending = this.pending.get(resp.seq);
    if (!pending) return false;

    clearTimeout(pending.timer);
    this.pending.delete(resp.seq);

    if (resp.code !== 0) {
      pending.reject(new IMError(resp.code, resp.msg || "Unknown error", resp.detail));
    } else {
      pending.resolve(resp);
    }
    return true;
  }

  /** 标记所有 pending 请求为失败（如断开连接） */
  rejectAll(reason: string): void {
    for (const [seq, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new IMError(-1, reason));
    }
    this.pending.clear();
  }

  get pendingCount(): number {
    return this.pending.size;
  }
}

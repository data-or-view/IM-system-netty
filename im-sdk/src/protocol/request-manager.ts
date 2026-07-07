import { type WSRequest, type WSResponse, IMConnectionError, IMError, IMServerError, IMTimeoutError } from "../types.js";
import { SDK_DEFAULTS } from "../config/defaults.js";
import { PROTOCOL_SUCCESS_CODE, WS_FRAME_FIELD } from "./constants.js";

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
  requestIdFactory?: () => string;

  constructor(requestTimeout: number = SDK_DEFAULTS.requestTimeoutMs) {
    this.requestTimeout = requestTimeout;
  }

  /** 创建一个 WS 请求帧，返回 Promise */
  createRequest(op: string, params: Record<string, unknown> = {}): { frame: WSRequest; promise: Promise<WSResponse> } {
    const seq = ++this.seq;
    const frame: WSRequest = { op, seq, [WS_FRAME_FIELD.REQUEST_ID]: this.nextRequestId(), ...params };

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

    if (resp.code !== PROTOCOL_SUCCESS_CODE) {
      pending.reject(new IMServerError(resp.code, resp.msg || "Unknown error", resp.detail));
    } else {
      pending.resolve(resp);
    }
    return true;
  }

  /** 标记指定 pending 请求为失败（如发送前发现连接不可用） */
  reject(seq: number, err: IMError): boolean {
    const pending = this.pending.get(seq);
    if (!pending) return false;

    clearTimeout(pending.timer);
    this.pending.delete(seq);
    pending.reject(err);
    return true;
  }

  /** 标记所有 pending 请求为失败（如断开连接） */
  rejectAll(reason: string): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(new IMConnectionError(reason));
    }
    this.pending.clear();
  }

  get pendingCount(): number {
    return this.pending.size;
  }

  nextRequestId(): string {
    return (this.requestIdFactory ?? defaultRequestId)();
  }
}

function defaultRequestId(): string {
  const random = Math.random().toString(36).slice(2, 10);
  return `req_${Date.now().toString(36)}_${random}`;
}

/**
 * IM SDK 集成 —— 创建并导出 SDK 单例供 im-web 使用。
 *
 * 本地开发默认连接后端 Netty 服务：
 * - WebSocket: ws://127.0.0.1:8083/ws
 * - HTTP: http://127.0.0.1:8084
 *
 * 可通过 Vite 环境变量覆盖：VITE_WS_URL / VITE_HTTP_URL。
 */

import { createIM } from "im-sdk";

const DEFAULT_WS_URL = "ws://127.0.0.1:8083/ws";
const DEFAULT_HTTP_URL = "http://127.0.0.1:8084";

export const im = createIM({
  wsUrl: import.meta.env.VITE_WS_URL ?? DEFAULT_WS_URL,
  httpUrl: import.meta.env.VITE_HTTP_URL ?? DEFAULT_HTTP_URL,
  connectTimeout: 5000,
  syncOnReconnect: true,
  syncConversations: () => {
    const raw = sessionStorage.getItem("im_sync_cursors");
    if (!raw) return [];
    try {
      const parsed = JSON.parse(raw) as unknown;
      if (!Array.isArray(parsed)) return [];
      return parsed
        .filter((item): item is { conversationId: string; lastSeq: number } =>
          typeof item === "object" &&
          item !== null &&
          typeof (item as { conversationId?: unknown }).conversationId === "string" &&
          typeof (item as { lastSeq?: unknown }).lastSeq === "number",
        );
    } catch {
      return [];
    }
  },
  getToken: () => localStorage.getItem("im_token"),
  getRefreshToken: () => localStorage.getItem("im_refreshToken"),
  onTokenChanged: (tokens) => {
    if (tokens.token) {
      localStorage.setItem("im_token", tokens.token);
    } else {
      localStorage.removeItem("im_token");
    }
    if (tokens.refreshToken) {
      localStorage.setItem("im_refreshToken", tokens.refreshToken);
    } else {
      localStorage.removeItem("im_refreshToken");
    }
  },
});

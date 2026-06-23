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
import { SDK_CONNECT_TIMEOUT_MS, SDK_REQUEST_TIMEOUT_MS, DEV_HTTP_URL, DEV_WS_URL } from "@/config/runtime";
import {
  AUTH_REFRESH_TOKEN_KEY,
  AUTH_TOKEN_KEY,
  getStoredAuthUserId,
  syncCursorsKey,
} from "@/config/storage-keys";

export const im = createIM({
  wsUrl: import.meta.env.VITE_WS_URL ?? DEV_WS_URL,
  httpUrl: import.meta.env.VITE_HTTP_URL ?? DEV_HTTP_URL,
  connectTimeout: SDK_CONNECT_TIMEOUT_MS,
  requestTimeout: SDK_REQUEST_TIMEOUT_MS,
  syncOnReconnect: true,
  syncConversations: () => {
    const raw = sessionStorage.getItem(syncCursorsKey(getStoredAuthUserId()));
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
  getToken: () => localStorage.getItem(AUTH_TOKEN_KEY),
  getRefreshToken: () => localStorage.getItem(AUTH_REFRESH_TOKEN_KEY),
  onTokenChanged: (tokens) => {
    if (tokens.token) {
      localStorage.setItem(AUTH_TOKEN_KEY, tokens.token);
    } else {
      localStorage.removeItem(AUTH_TOKEN_KEY);
    }
    if (tokens.refreshToken) {
      localStorage.setItem(AUTH_REFRESH_TOKEN_KEY, tokens.refreshToken);
    } else {
      localStorage.removeItem(AUTH_REFRESH_TOKEN_KEY);
    }
  },
});

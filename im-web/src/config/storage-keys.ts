import { DEV_HTTP_URL, DEV_WS_URL } from "@/config/runtime";

const PREFIX = "im";
const DEFAULT_USER_SCOPE = "anonymous";

function envValue(name: string): string | undefined {
  const value = import.meta.env[name] as string | undefined;
  return value && value.trim() ? value.trim() : undefined;
}

function storageEnvironment(): string {
  return envValue("VITE_STORAGE_NAMESPACE")
    ?? [
      import.meta.env.MODE || "development",
      envValue("VITE_HTTP_URL") ?? DEV_HTTP_URL,
      envValue("VITE_WS_URL") ?? DEV_WS_URL,
    ].join("|");
}

function part(value: string | null | undefined): string {
  return encodeURIComponent((value && value.trim()) || DEFAULT_USER_SCOPE);
}

function scopedKey(name: string): string {
  return `${PREFIX}:${part(storageEnvironment())}:${name}`;
}

function userScopedKey(userId: string | null | undefined, name: string): string {
  return `${PREFIX}:${part(storageEnvironment())}:${part(userId)}:${name}`;
}

export const AUTH_USER_ID_KEY = scopedKey("userId");
export const AUTH_TOKEN_KEY = scopedKey("token");
export const AUTH_REFRESH_TOKEN_KEY = scopedKey("refreshToken");

export function syncCursorsKey(userId: string | null | undefined): string {
  return userScopedKey(userId, "sync_cursors");
}

export function getStoredAuthUserId(): string | null {
  return localStorage.getItem(AUTH_USER_ID_KEY);
}

export function clearStoredSyncCursors(userId: string | null | undefined): void {
  if (!userId) return;
  sessionStorage.removeItem(syncCursorsKey(userId));
}

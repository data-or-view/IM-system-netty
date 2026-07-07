export const APP_ROUTES = {
  login: "/login",
  chat: "/chat",
  createGroup: "/chat/create-group",
  user: (userId: string) => `/chat/user/${encodeURIComponent(userId)}`,
  group: (groupId: string) => `/chat/group/${encodeURIComponent(groupId)}`,
  loginWithRedirect: (target: string) => `/login?redirect=${encodeURIComponent(safeInternalPath(target))}`,
} as const;

export function getRedirectTarget(search: string): string {
  const redirect = new URLSearchParams(search).get("redirect");
  return safeInternalPath(redirect);
}

function safeInternalPath(value: string | null | undefined): string {
  if (!value || !value.startsWith("/") || value.startsWith("//")) {
    return APP_ROUTES.chat;
  }
  if (/^[a-z][a-z0-9+.-]*:/i.test(value)) {
    return APP_ROUTES.chat;
  }
  if (value === APP_ROUTES.login || value.startsWith(`${APP_ROUTES.login}?`)) {
    return APP_ROUTES.chat;
  }
  return value;
}

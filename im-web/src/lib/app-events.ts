import type { AppErrorNotice } from "@/lib/app-errors";
import type { FriendApply, GroupApply } from "@/store/store-types";

export const APP_EVENT_TYPES = {
  appError: "im:app-error",
  friendApplyUpdated: "im:friend-apply-updated",
  groupApplyUpdated: "im:group-apply-updated",
} as const;

export interface AppEventPayloadMap {
  [APP_EVENT_TYPES.appError]: AppErrorNotice;
  [APP_EVENT_TYPES.friendApplyUpdated]: FriendApply;
  [APP_EVENT_TYPES.groupApplyUpdated]: GroupApply;
}

export type AppEventType = keyof AppEventPayloadMap;

export function emitAppEvent<T extends AppEventType>(type: T, detail: AppEventPayloadMap[T]): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(type, { detail }));
}

export function listenAppEvent<T extends AppEventType>(
  type: T,
  handler: (detail: AppEventPayloadMap[T], event: CustomEvent<AppEventPayloadMap[T]>) => void,
): () => void {
  if (typeof window === "undefined") return () => {};
  const listener = (event: Event) => {
    const customEvent = event as CustomEvent<AppEventPayloadMap[T]>;
    handler(customEvent.detail, customEvent);
  };
  window.addEventListener(type, listener);
  return () => window.removeEventListener(type, listener);
}

import { useEffect, type Dispatch, type MutableRefObject } from "react";
import type { SystemMessageSummary } from "im-sdk";
import { im } from "@/sdk/im-sdk";
import { AUTH_TOKEN_KEY } from "@/config/storage-keys";
import { toViewMessage } from "@/lib/messages";
import { applyDomainEvent, latestMessage, type PushRefreshTask } from "@/store/domain";
import { currentStoredUserId, persistTokens } from "@/store/store-helpers";
import {
  FRIEND_APPLY_UPDATED_EVENT,
  GROUP_APPLY_UPDATED_EVENT,
  type Action,
  type FriendApply,
  type GroupApply,
  type Message,
  type State,
} from "@/store/store-types";

interface StoreSdkEventsOptions {
  stateRef: MutableRefObject<State>;
  dispatch: Dispatch<Action>;
  hydrateAfterAuth: () => Promise<void>;
  markConversationRead: (conversationId: string, seq?: number) => Promise<void>;
  runRefreshTasks: (tasks: PushRefreshTask[]) => void;
}

export function useStoreSdkEvents({
  stateRef,
  dispatch,
  hydrateAfterAuth,
  markConversationRead,
  runRefreshTasks,
}: StoreSdkEventsOptions) {
  useEffect(() => {
    const unsubConnection = im.on("connectionStateChanged", (connectionState) => {
      dispatch({ type: "SET_CONNECTED", connected: connectionState === "connected" });
      if (connectionState === "connected" && localStorage.getItem(AUTH_TOKEN_KEY)) {
        void hydrateAfterAuth();
      }
    });

    const unsubMessageBatch = im.on("messageBatch", (sdkMsgs) => {
      const currentState = stateRef.current;
      const activeConversationId = currentState.activeConversationId;
      const currentUserId = currentStoredUserId(currentState.userId);
      const result = applyDomainEvent(currentState, {
        type: "MESSAGE_RECEIVED",
        messages: sdkMsgs,
        currentUserId,
        activeConversationId,
      });
      dispatch({ type: "REPLACE_DOMAIN_STATE", state: result.state });
      runRefreshTasks(result.refreshTasks);

      const grouped = new Map<string, Message[]>();
      for (const sdkMsg of sdkMsgs) {
        const msg = toViewMessage(sdkMsg);
        if (!msg.conversationId) continue;
        grouped.set(msg.conversationId, [...(grouped.get(msg.conversationId) || []), msg]);
      }
      for (const [conversationId, msgs] of grouped) {
        if (activeConversationId === conversationId) {
          const latest = latestMessage(msgs);
          if (latest) void markConversationRead(conversationId, latest.seq);
        }
      }
    });

    const unsubRevoke = im.on("messageRevoked", (event) => {
      const result = applyDomainEvent(stateRef.current, { type: "MESSAGE_REVOKED", event });
      dispatch({ type: "REPLACE_DOMAIN_STATE", state: result.state });
      runRefreshTasks(result.refreshTasks);
    });

    const unsubFriendRequest = im.on("friendRequest", (apply: FriendApply) => {
      const result = applyDomainEvent(stateRef.current, { type: "FRIEND_APPLY_UPDATED", apply });
      dispatch({ type: "REPLACE_DOMAIN_STATE", state: result.state });
      runRefreshTasks(result.refreshTasks);
      window.dispatchEvent(new CustomEvent(FRIEND_APPLY_UPDATED_EVENT, { detail: apply }));
    });

    const unsubGroupApply = im.on("groupApply", (apply: GroupApply) => {
      const result = applyDomainEvent(stateRef.current, { type: "GROUP_APPLY_UPDATED", apply });
      dispatch({ type: "REPLACE_DOMAIN_STATE", state: result.state });
      runRefreshTasks(result.refreshTasks);
      window.dispatchEvent(new CustomEvent(GROUP_APPLY_UPDATED_EVENT, { detail: apply }));
    });

    const unsubSystemMessage = im.on("systemMessage", (message: SystemMessageSummary) => {
      const result = applyDomainEvent(stateRef.current, { type: "SYSTEM_MESSAGE_RECEIVED", message });
      dispatch({ type: "REPLACE_DOMAIN_STATE", state: result.state });
      runRefreshTasks(result.refreshTasks);
    });

    const unsubReconnectSync = im.on("reconnectSync", () => {
      const result = applyDomainEvent(stateRef.current, { type: "RECONNECTED_SYNCED" });
      dispatch({ type: "REPLACE_DOMAIN_STATE", state: result.state });
      runRefreshTasks(result.refreshTasks);
    });

    const unsubTokenChanged = im.on("tokenChanged", (tokens) => {
      persistTokens(tokens);
      dispatch({
        type: "SET_TOKENS",
        token: tokens.token ?? null,
        refreshToken: tokens.refreshToken ?? null,
      });
    });

    return () => {
      unsubConnection();
      unsubMessageBatch();
      unsubRevoke();
      unsubFriendRequest();
      unsubGroupApply();
      unsubSystemMessage();
      unsubReconnectSync();
      unsubTokenChanged();
    };
  }, [dispatch, hydrateAfterAuth, markConversationRead, runRefreshTasks, stateRef]);
}

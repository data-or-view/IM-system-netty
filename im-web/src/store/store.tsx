/**
 * IM 应用状态管理 —— React Context + useReducer 的 Provider 编排层。
 *
 * 纯状态结构、reducer 和缓存/存储 helper 已拆到相邻模块，避免 Provider
 * 同时承担类型定义、领域合并和 SDK 副作用细节。
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useReducer,
  useRef,
  type ReactNode,
} from "react";
import { createClientMsgId } from "im-sdk";
import { im } from "@/sdk/im-sdk";
import { APP_BEHAVIOR } from "@/config/app-behavior";
import { AUTH_USER_ID_KEY } from "@/config/storage-keys";
import { toOptimisticMessage } from "@/lib/messages";
import {
  normalizeConversation,
  type PushRefreshTask,
} from "@/store/domain";
import { initialState, reducer } from "@/store/store-reducer";
import {
  cacheFresh,
  clearStoredAuth,
  currentStoredUserId,
  groupConversationId,
  groupInfoFromConversation,
  persistSyncCursors,
  persistTokens,
} from "@/store/store-helpers";
import { useStoreSdkEvents } from "@/store/useStoreSdkEvents";
import type {
  Conversation,
  FriendInfo,
  GroupInfo,
  GroupMember,
  OpenGroupChatInput,
  OpenSingleChatInput,
  StoreContextType,
  UserInfo,
} from "@/store/store-types";
export {
  FRIEND_APPLY_UPDATED_EVENT,
  GROUP_APPLY_UPDATED_EVENT,
  SYSTEM_CONVERSATION_ID,
} from "@/store/store-types";
export type {
  Conversation,
  FriendInfo,
  GroupApply,
  GroupInfo,
  GroupMember,
  Message,
  OpenGroupChatInput,
  OpenSingleChatInput,
  UserInfo,
} from "@/store/store-types";

const StoreContext = createContext<StoreContextType | null>(null);

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);
  const stateRef = useRef(state);
  const lastReadSeqRef = useRef<Record<string, number>>({});
  const refreshQueueRef = useRef<Set<PushRefreshTask>>(new Set());
  const refreshTimerRef = useRef<number | null>(null);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  const fetchConversations = useCallback(async () => {
    try {
      const list = ((await im.conversation.list()) as unknown as Conversation[]).map(normalizeConversation);
      dispatch({ type: "SET_CONVERSATIONS", list });
    } catch (err) {
      console.error("fetchConversations failed:", err);
    }
  }, []);

  const markConversationRead = useCallback(async (conversationId: string, seq?: number) => {
    const currentState = stateRef.current;
    const conversation = currentState.conversations.find((item) => item.conversationId === conversationId);
    if (!conversation) return;

    const nextSeq = seq ?? (currentState.messages[conversationId] || []).reduce(
      (max, msg) => Math.max(max, msg.seq || 0),
      0,
    );
    if (nextSeq <= 0) {
      dispatch({ type: "MARK_READ_LOCAL", conversationId });
      return;
    }

    const lastSeq = lastReadSeqRef.current[conversationId] ?? -1;
    if (nextSeq <= lastSeq && conversation.unreadCount === 0) return;
    lastReadSeqRef.current[conversationId] = nextSeq;
    dispatch({ type: "MARK_READ_LOCAL", conversationId });

    try {
      const result = await im.conversation.read(conversationId, nextSeq || undefined);
      dispatch({
        type: "UPDATE_CONVERSATION_UNREAD",
        conversationId: result.conversationId || conversationId,
        unreadCount: result.unreadCount ?? 0,
      });
    } catch (err) {
      console.error("markConversationRead failed:", err);
    }
  }, []);

  const fetchMyGroups = useCallback(async () => {
    try {
      const groups = await im.group.list();
      dispatch({ type: "SET_MY_GROUPS", list: groups as unknown as GroupInfo[] });
    } catch (err) {
      console.error("fetchMyGroups failed:", err);
      try {
        const conversations = ((await im.conversation.list()) as unknown as Conversation[]).map(normalizeConversation);
        dispatch({ type: "SET_MY_GROUPS", list: groupInfoFromConversation(conversations) });
      } catch (fallbackErr) {
        console.error("fetchMyGroups fallback failed:", fallbackErr);
      }
    }
  }, []);

  const fetchFriends = useCallback(async () => {
    try {
      const list = await im.friend.list();
      dispatch({ type: "SET_FRIENDS", list: list as unknown as FriendInfo[] });
    } catch (err) {
      console.error("fetchFriends failed:", err);
    }
  }, []);

  const fetchUnhandledApplyCount = useCallback(async () => {
    try {
      const count = await im.friend.unhandledApplyCount();
      dispatch({ type: "SET_UNHANDLED_APPLY_COUNT", count });
    } catch (err) {
      console.error("fetchUnhandledApplyCount failed:", err);
    }
  }, []);

  const fetchUnhandledGroupApplyCount = useCallback(async () => {
    try {
      const count = await im.group.unhandledApplyCount();
      dispatch({ type: "SET_UNHANDLED_GROUP_APPLY_COUNT", count });
    } catch (err) {
      console.error("fetchUnhandledGroupApplyCount failed:", err);
    }
  }, []);

  const refreshSystemMessages = useCallback(async () => {
    try {
      const [messages, unread] = await Promise.all([
        im.system.messages({ limit: APP_BEHAVIOR.systemMessages.listLimit }),
        im.system.unreadCount(),
      ]);
      dispatch({ type: "SET_SYSTEM_MESSAGES", messages, unreadCount: unread.count ?? 0 });
    } catch (err) {
      console.error("refreshSystemMessages failed:", err);
    }
  }, []);

  const flushRefreshTasks = useCallback(() => {
    refreshTimerRef.current = null;
    const unique = new Set(refreshQueueRef.current);
    refreshQueueRef.current.clear();
    if (unique.has("conversations")) void fetchConversations();
    if (unique.has("friends")) void fetchFriends();
    if (unique.has("myGroups")) void fetchMyGroups();
    if (unique.has("friendApplyCount")) void fetchUnhandledApplyCount();
    if (unique.has("groupApplyCount")) void fetchUnhandledGroupApplyCount();
    if (unique.has("systemUnreadCount")) void refreshSystemMessages();
  }, [
    fetchConversations,
    fetchFriends,
    fetchMyGroups,
    fetchUnhandledApplyCount,
    fetchUnhandledGroupApplyCount,
    refreshSystemMessages,
  ]);

  const runRefreshTasks = useCallback((tasks: PushRefreshTask[]) => {
    for (const task of tasks) {
      refreshQueueRef.current.add(task);
    }
    if (refreshTimerRef.current !== null) return;
    refreshTimerRef.current = window.setTimeout(flushRefreshTasks, APP_BEHAVIOR.refresh.debounceMs);
  }, [flushRefreshTasks]);

  const fetchUserProfile = useCallback(async (userId: string, options?: { force?: boolean }) => {
    try {
      const currentState = stateRef.current;
      if (
        !options?.force &&
        currentState.userProfileCache[userId] &&
        cacheFresh(currentState.userProfileCachedAt[userId], APP_BEHAVIOR.cache.userProfileTtlMs)
      ) {
        return;
      }
      const info = userId === currentStoredUserId(state.userId)
        ? await im.user.me()
        : await im.user.info(userId);
      dispatch({ type: "SET_USER_PROFILE", userId, info: info as unknown as UserInfo });
    } catch (err) {
      console.error("fetchUserProfile failed:", err);
    }
  }, [state.userId]);

  const hydrateAfterAuth = useCallback(async (currentUserId = state.userId) => {
    await Promise.all([
      currentUserId ? fetchUserProfile(currentUserId) : Promise.resolve(),
      fetchConversations(),
      fetchFriends(),
      fetchMyGroups(),
      fetchUnhandledApplyCount(),
      fetchUnhandledGroupApplyCount(),
      refreshSystemMessages(),
    ]);
  }, [fetchConversations, fetchFriends, fetchMyGroups, fetchUnhandledApplyCount, fetchUnhandledGroupApplyCount, fetchUserProfile, refreshSystemMessages, state.userId]);

  useStoreSdkEvents({
    stateRef,
    dispatch,
    hydrateAfterAuth,
    markConversationRead,
    runRefreshTasks,
  });

  useEffect(() => {
    const conversationId = state.activeConversationId;
    if (!conversationId) return;
    const latestSeq = (state.messages[conversationId] || []).reduce(
      (max, msg) => Math.max(max, msg.seq || 0),
      0,
    );
    void markConversationRead(conversationId, latestSeq || undefined);
  }, [markConversationRead, state.activeConversationId, state.messages]);

  useEffect(() => {
    persistSyncCursors(state.userId, state.messages);
  }, [state.messages, state.userId]);

  useEffect(() => {
    if (state.token && state.userId && im.state === "disconnected") {
      im.connect();
    }
  }, [state.token, state.userId]);

  useEffect(() => () => {
    if (refreshTimerRef.current !== null) {
      window.clearTimeout(refreshTimerRef.current);
    }
  }, []);

  const login = useCallback(async (userId: string, password?: string) => {
    localStorage.setItem(AUTH_USER_ID_KEY, userId);
    const tokens = await im.login(userId, password);
    persistTokens(tokens);
    dispatch({ type: "SET_AUTH", userId, token: tokens.token ?? "", refreshToken: tokens.refreshToken });
    await hydrateAfterAuth(userId);
  }, [hydrateAfterAuth]);

  const register = useCallback(async (params: { password?: string; nickname?: string; faceUrl?: string }) => {
    const result = await im.user.register(params);
    localStorage.setItem(AUTH_USER_ID_KEY, result.userId);
    await login(result.userId, params.password);
    return result.userId;
  }, [login]);

  const logout = useCallback(() => {
    clearStoredAuth(state.userId);
    im.disconnect();
    dispatch({ type: "LOGOUT" });
  }, [state.userId]);

  const sendMessage = useCallback(async (toUserId: string, content: string) => {
    const messageContent = { text: content };
    const clientMsgId = createClientMsgId();
    await im.waitConnected();
    const ack = await im.message.send({ toUserId, contentType: "text", content: messageContent, clientMsgId });
    const currentUserId = currentStoredUserId(state.userId) || "";
    const msg = toOptimisticMessage(ack, currentUserId, "text", messageContent);
    if (msg.conversationId) {
      dispatch({ type: "APPEND_MESSAGE", conversationId: msg.conversationId, msg });
      dispatch({
        type: "UPDATE_CONVERSATION_LATEST",
        conversationId: msg.conversationId,
        latestMsg: msg.content,
        latestMsgSendTime: msg.createTime,
      });
      void fetchConversations();
    }
    return msg;
  }, [fetchConversations, state.userId]);

  const searchUser = useCallback(async (keyword: string, limit: number = APP_BEHAVIOR.search.defaultLimit) => {
    const list = await im.friend.search(keyword, limit);
    dispatch({ type: "SET_SEARCH_USERS", list: list as unknown as UserInfo[] });
  }, []);

  const searchGroup = useCallback(async (keyword: string, limit: number = APP_BEHAVIOR.search.defaultLimit) => {
    const list = await im.group.search(keyword, limit);
    dispatch({ type: "SET_SEARCH_GROUPS", list: list as unknown as GroupInfo[] });
  }, []);

  const applyFriend = useCallback(async (targetUserId: string, reqMsg?: string) => {
    await im.friend.apply(targetUserId, reqMsg);
    await fetchFriends();
  }, [fetchFriends]);

  const removeFriend = useCallback(async (targetUserId: string) => {
    await im.friend.remove(targetUserId);
    dispatch({ type: "REMOVE_FRIEND", friendUserId: targetUserId });
  }, []);

  const joinGroup = useCallback(async (groupId: string, reqMsg?: string) => {
    const result = await im.group.join(groupId, reqMsg);
    if (result.status === "JOINED" || result.status === "ALREADY_MEMBER") {
      await Promise.all([fetchMyGroups(), fetchConversations()]);
    } else {
      await fetchUnhandledGroupApplyCount();
    }
    return result;
  }, [fetchConversations, fetchMyGroups, fetchUnhandledGroupApplyCount]);

  const quitGroup = useCallback(async (groupId: string) => {
    await im.group.quit(groupId);
    dispatch({ type: "REMOVE_CONVERSATION", conversationId: groupConversationId(groupId) });
    await Promise.all([fetchMyGroups(), fetchConversations()]);
  }, [fetchConversations, fetchMyGroups]);

  const approveFriend = useCallback(async (fromUserId: string, agreed: boolean) => {
    try {
      await im.friend.approve(fromUserId, agreed);
      await Promise.all([fetchFriends(), fetchUnhandledApplyCount()]);
    } catch (err) {
      console.error("approveFriend failed:", err);
      throw err;
    }
  }, [fetchFriends, fetchUnhandledApplyCount]);

  const approveGroupApply = useCallback(async (groupId: string, userId: string, agreed: boolean) => {
    try {
      await im.group.approveApply(groupId, userId, agreed);
      await Promise.all([fetchMyGroups(), fetchUnhandledGroupApplyCount()]);
    } catch (err) {
      console.error("approveGroupApply failed:", err);
      throw err;
    }
  }, [fetchMyGroups, fetchUnhandledGroupApplyCount]);

  const fetchGroupMembers = useCallback(async (groupId: string, options?: { force?: boolean }) => {
    try {
      const currentState = stateRef.current;
      if (
        !options?.force &&
        currentState.groupMembers[groupId] &&
        cacheFresh(currentState.groupMembersCachedAt[groupId], APP_BEHAVIOR.cache.groupMembersTtlMs)
      ) {
        return;
      }
      const members = await im.group.members(groupId);
      dispatch({ type: "SET_GROUP_MEMBERS", groupId, members: members as unknown as GroupMember[] });
    } catch (err) {
      console.error("fetchGroupMembers failed:", err);
    }
  }, []);

  const fetchGroupInfo = useCallback(async (groupId: string, options?: { force?: boolean }) => {
    try {
      const currentState = stateRef.current;
      if (
        !options?.force &&
        currentState.groupInfoCache[groupId] &&
        cacheFresh(currentState.groupInfoCachedAt[groupId], APP_BEHAVIOR.cache.groupInfoTtlMs)
      ) {
        return;
      }
      const info = await im.group.info(groupId);
      dispatch({ type: "SET_GROUP_INFO", groupId, info: info as unknown as GroupInfo });
    } catch (err) {
      console.error("fetchGroupInfo failed:", err);
    }
  }, []);

  const openSingleChat = useCallback((input: OpenSingleChatInput) => {
    dispatch({ type: "OPEN_SINGLE_CHAT", input });
  }, []);

  const openGroupChat = useCallback((input: OpenGroupChatInput) => {
    dispatch({ type: "OPEN_GROUP_CHAT", input });
  }, []);

  const removeConversationLocal = useCallback((conversationId: string) => {
    dispatch({ type: "REMOVE_CONVERSATION", conversationId });
  }, []);

  const refreshAfterMembershipChanged = useCallback(async () => {
    await Promise.all([fetchFriends(), fetchMyGroups(), fetchConversations()]);
  }, [fetchConversations, fetchFriends, fetchMyGroups]);

  return (
    <StoreContext.Provider
      value={{
        state,
        dispatch,
        login,
        register,
        logout,
        sendMessage,
        fetchConversations,
        fetchFriends,
        searchUser,
        applyFriend,
        removeFriend,
        searchGroup,
        joinGroup,
        quitGroup,
        fetchMyGroups,
        approveFriend,
        fetchUnhandledApplyCount,
        approveGroupApply,
        fetchUnhandledGroupApplyCount,
        fetchGroupMembers,
        fetchGroupInfo,
        fetchUserProfile,
        markConversationRead,
        refreshSystemMessages,
        openSingleChat,
        openGroupChat,
        removeConversationLocal,
        refreshAfterMembershipChanged,
      }}
    >
      {children}
    </StoreContext.Provider>
  );
}

export function useStore() {
  const ctx = useContext(StoreContext);
  if (!ctx) throw new Error("useStore must be used within StoreProvider");
  return ctx;
}

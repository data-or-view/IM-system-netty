/**
 * IM 应用状态管理 —— 基于 React Context + useReducer。
 *
 * 底层使用 im-sdk 的 Promise API，向上提供与旧版兼容的 useStore 接口。
 * SDK 事件（新消息、连接状态等）自动同步到 React state。
 */

import React, {
  createContext,
  useContext,
  useReducer,
  useCallback,
  useEffect,
  useRef,
  type ReactNode,
} from "react";
import { im } from "@/sdk/im-sdk";
import { ConversationType, MessageReceiveOption, createClientMsgId } from "im-sdk";
import type { SystemMessageInboxItem, SystemMessageSummary, TokenPair, UserInfo as SDKUserInfo, FriendInfo as SDKFriendInfo, FriendApply as SDKFriendApply, GroupInfo as SDKGroupInfo, GroupMember as SDKGroupMember, GroupApply as SDKGroupApply, Conversation as SDKConversation, GroupJoinResponse } from "im-sdk";
import { AUTH_REFRESH_TOKEN_KEY, AUTH_TOKEN_KEY, AUTH_USER_ID_KEY, SYNC_CURSORS_KEY } from "@/config/storage-keys";
import { toOptimisticMessage, toViewMessage, type ViewMessage } from "@/lib/messages";
import {
  applyDomainEvent,
  latestMessage,
  mergeConversationMessages,
  normalizeConversation,
  setConversationsKeepingActive,
  sortConversations,
  upsertConversation,
  type PushRefreshTask,
} from "@/store/domain";

// ========== 类型（与 SDK 类型一致） ==========

export type Conversation = SDKConversation;
export type UserInfo = SDKUserInfo;
export type FriendInfo = SDKFriendInfo;
export type GroupInfo = SDKGroupInfo;
export type GroupMember = SDKGroupMember;
export type GroupApply = SDKGroupApply;

export type Message = ViewMessage;

// ========== State ==========

export const SYSTEM_CONVERSATION_ID = "__system_notifications__";
export const FRIEND_APPLY_UPDATED_EVENT = "im:friend-apply-updated";
export const GROUP_APPLY_UPDATED_EVENT = "im:group-apply-updated";

interface State {
  token: string | null;
  refreshToken: string | null;
  userId: string | null;
  connected: boolean;

  conversations: Conversation[];
  messages: Record<string, Message[]>;
  friends: FriendInfo[];

  myGroups: GroupInfo[];
  searchUsers: UserInfo[];
  searchGroups: GroupInfo[];
  unhandledApplyCount: number;
  unhandledGroupApplyCount: number;

  activeConversationId: string | null;
  systemMessages: SystemMessageInboxItem[];
  systemUnreadCount: number;
  latestSystemMessage: SystemMessageSummary | null;
  groupMembers: Record<string, GroupMember[]>;
  groupInfoCache: Record<string, GroupInfo>;
  userProfileCache: Record<string, UserInfo>;
}

const initialState: State = {
  token: localStorage.getItem(AUTH_TOKEN_KEY),
  refreshToken: localStorage.getItem(AUTH_REFRESH_TOKEN_KEY),
  userId: localStorage.getItem(AUTH_USER_ID_KEY),
  connected: false,
  conversations: [],
  messages: {},
  friends: [],
  myGroups: [],
  searchUsers: [],
  searchGroups: [],
  unhandledApplyCount: 0,
  unhandledGroupApplyCount: 0,
  activeConversationId: null,
  systemMessages: [],
  systemUnreadCount: 0,
  latestSystemMessage: null,
  groupMembers: {},
  groupInfoCache: {},
  userProfileCache: {},
};

// ========== Actions ==========

type Action =
  | { type: "SET_CONNECTED"; connected: boolean }
  | { type: "SET_AUTH"; userId: string; token: string; refreshToken?: string | null }
  | { type: "SET_TOKENS"; token?: string | null; refreshToken?: string | null }
  | { type: "LOGOUT" }
  | { type: "SET_CONVERSATIONS"; list: Conversation[] }
  | { type: "APPEND_MESSAGE"; conversationId: string; msg: Message }
  | { type: "ADD_MESSAGES"; conversationId: string; msgs: Message[] }
  | { type: "REVOKE_MESSAGE"; conversationId: string; seq: number }
  | { type: "SET_FRIENDS"; list: FriendInfo[] }
  | { type: "SET_MY_GROUPS"; list: GroupInfo[] }
  | { type: "SET_SEARCH_USERS"; list: UserInfo[] }
  | { type: "SET_SEARCH_GROUPS"; list: GroupInfo[] }
  | { type: "SET_UNHANDLED_APPLY_COUNT"; count: number }
  | { type: "SET_UNHANDLED_GROUP_APPLY_COUNT"; count: number }
  | { type: "SET_ACTIVE_CONVERSATION"; conversationId: string | null }
  | { type: "SET_SYSTEM_MESSAGES"; messages: SystemMessageInboxItem[]; unreadCount: number }
  | { type: "ADD_FRIEND"; friend: FriendInfo }
  | { type: "REMOVE_FRIEND"; friendUserId: string }
  | { type: "ADD_CONVERSATION"; conversation: Conversation }
  | { type: "REMOVE_CONVERSATION"; conversationId: string }
  | { type: "OPEN_SINGLE_CHAT"; input: OpenSingleChatInput }
  | { type: "OPEN_GROUP_CHAT"; input: OpenGroupChatInput }
  | { type: "MARK_READ_LOCAL"; conversationId: string }
  | { type: "UPSERT_SENT_MESSAGE"; previousConversationId?: string; conversation: Conversation; msg: Message }
  | { type: "UPDATE_CONVERSATION_LATEST"; conversationId: string; latestMsg: string; latestMsgSendTime: number; incoming?: boolean }
  | { type: "UPDATE_CONVERSATION_UNREAD"; conversationId: string; unreadCount: number }
  | { type: "SET_GROUP_MEMBERS"; groupId: string; members: GroupMember[] }
  | { type: "SET_GROUP_INFO"; groupId: string; info: GroupInfo }
  | { type: "SET_USER_PROFILE"; userId: string; info: UserInfo }
  | { type: "REPLACE_DOMAIN_STATE"; state: Pick<State, "userId" | "activeConversationId" | "conversations" | "messages" | "systemMessages" | "systemUnreadCount" | "latestSystemMessage"> };

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case "SET_CONNECTED":
      return { ...state, connected: action.connected };
    case "SET_AUTH":
      return {
        ...state,
        userId: action.userId,
        token: action.token,
        refreshToken: action.refreshToken ?? state.refreshToken,
      };
    case "SET_TOKENS":
      return {
        ...state,
        token: action.token !== undefined ? action.token : state.token,
        refreshToken: action.refreshToken !== undefined ? action.refreshToken : state.refreshToken,
      };
    case "LOGOUT":
      return { ...initialState, token: null, refreshToken: null, userId: null, connected: false };
    case "SET_CONVERSATIONS": {
      return setConversationsKeepingActive(state, action.list) as State;
    }
    case "APPEND_MESSAGE": {
      const existing = state.messages[action.conversationId] || [];
      const merged = mergeConversationMessages(existing, [action.msg]);
      if (merged === existing) return state;
      return {
        ...state,
        messages: { ...state.messages, [action.conversationId]: merged },
      };
    }
    case "ADD_MESSAGES": {
      const existing = state.messages[action.conversationId] || [];
      const merged = mergeConversationMessages(existing, action.msgs);
      if (merged === existing) return state;
      return {
        ...state,
        messages: {
          ...state.messages,
          [action.conversationId]: merged,
        },
      };
    }
    case "REVOKE_MESSAGE": {
      const existing = state.messages[action.conversationId] || [];
      if (existing.length === 0) return state;
      return {
        ...state,
        messages: {
          ...state.messages,
          [action.conversationId]: existing.map((m) =>
            m.seq === action.seq ? { ...m, contentType: 101, content: "消息已撤回" } : m
          ),
        },
      };
    }
    case "SET_FRIENDS":
      return { ...state, friends: action.list };
    case "SET_MY_GROUPS":
      return { ...state, myGroups: action.list };
    case "SET_SEARCH_USERS":
      return { ...state, searchUsers: action.list };
    case "SET_SEARCH_GROUPS":
      return { ...state, searchGroups: action.list };
    case "SET_UNHANDLED_APPLY_COUNT":
      return { ...state, unhandledApplyCount: action.count };
    case "SET_UNHANDLED_GROUP_APPLY_COUNT":
      return { ...state, unhandledGroupApplyCount: action.count };
    case "SET_ACTIVE_CONVERSATION": {
      const cleared = action.conversationId
        ? state.conversations.map((c) =>
            c.conversationId === action.conversationId ? { ...c, unreadCount: 0 } : c
          )
        : state.conversations;
      return { ...state, activeConversationId: action.conversationId, conversations: cleared };
    }
    case "SET_SYSTEM_MESSAGES":
      return {
        ...state,
        systemMessages: action.messages,
        systemUnreadCount: action.unreadCount,
        latestSystemMessage: action.messages[0] ?? state.latestSystemMessage,
      };
    case "ADD_FRIEND": {
      if (state.friends.some((f) => f.friendUserId === action.friend.friendUserId)) return state;
      return { ...state, friends: [...state.friends, action.friend] };
    }
    case "REMOVE_FRIEND":
      return { ...state, friends: state.friends.filter((f) => f.friendUserId !== action.friendUserId) };
    case "ADD_CONVERSATION": {
      return { ...state, conversations: upsertConversation(state.conversations, { conversation: action.conversation }) };
    }
    case "REMOVE_CONVERSATION": {
      const messages = { ...state.messages };
      delete messages[action.conversationId];
      return {
        ...state,
        conversations: state.conversations.filter((c) => c.conversationId !== action.conversationId),
        messages,
        activeConversationId: state.activeConversationId === action.conversationId ? null : state.activeConversationId,
      };
    }
    case "OPEN_SINGLE_CHAT": {
      const existing = state.conversations.find(
        (conv) => conv.conversationType === ConversationType.SINGLE && conv.userId === action.input.userId,
      );
      const conversation = existing
        ? normalizeConversation({
            ...existing,
            userId: existing.userId || action.input.userId,
            showName: existing.showName || action.input.nickname || action.input.userId,
            faceUrl: existing.faceUrl || action.input.faceUrl,
          })
        : createSingleConversation(state.userId || "", action.input);
      return {
        ...state,
        conversations: upsertConversation(state.conversations, { conversation }),
        activeConversationId: conversation.conversationId,
      };
    }
    case "OPEN_GROUP_CHAT": {
      const existing = state.conversations.find(
        (conv) => conv.conversationType === ConversationType.GROUP
          && (conv.groupId === action.input.groupId || conv.conversationId === groupConversationId(action.input.groupId)),
      );
      const conversation = existing ?? createGroupConversation(state.userId || "", action.input);
      return {
        ...state,
        conversations: upsertConversation(state.conversations, { conversation }),
        activeConversationId: conversation.conversationId,
      };
    }
    case "MARK_READ_LOCAL":
      return {
        ...state,
        conversations: state.conversations.map((c) =>
          c.conversationId === action.conversationId ? { ...c, unreadCount: 0 } : c
        ),
      };
    case "UPSERT_SENT_MESSAGE": {
      const nextId = action.conversation.conversationId;
      const previousId = action.previousConversationId;
      const carriedMessages = previousId && previousId !== nextId ? state.messages[previousId] || [] : [];
      const merged = mergeConversationMessages(
        state.messages[nextId] || [],
        [...carriedMessages, action.msg],
      );
      const messages = { ...state.messages, [nextId]: merged };
      if (previousId && previousId !== nextId) {
        delete messages[previousId];
      }
      return {
        ...state,
        messages,
        conversations: upsertConversation(state.conversations, {
          conversation: {
            ...action.conversation,
            latestMsg: action.msg.content,
            latestMsgSendTime: action.msg.createTime,
            unreadCount: 0,
          },
        }),
        activeConversationId: previousId && state.activeConversationId === previousId ? nextId : state.activeConversationId,
      };
    }
    case "UPDATE_CONVERSATION_LATEST": {
      const convExists = state.conversations.some((c) => c.conversationId === action.conversationId);
      if (!convExists) return state;
      const isActive = state.activeConversationId === action.conversationId;
      return {
        ...state,
        conversations: sortConversations(state.conversations.map((c) =>
          c.conversationId === action.conversationId
            ? {
                ...c,
                latestMsg: action.latestMsg,
                latestMsgSendTime: action.latestMsgSendTime,
                unreadCount: action.incoming && !isActive ? c.unreadCount + 1 : c.unreadCount,
              }
            : c
        )),
      };
    }
    case "UPDATE_CONVERSATION_UNREAD":
      return {
        ...state,
        conversations: state.conversations.map((c) =>
          c.conversationId === action.conversationId
            ? { ...c, unreadCount: action.unreadCount }
            : c
        ),
      };
    case "SET_GROUP_MEMBERS":
      return { ...state, groupMembers: { ...state.groupMembers, [action.groupId]: action.members } };
    case "SET_GROUP_INFO":
      return { ...state, groupInfoCache: { ...state.groupInfoCache, [action.groupId]: action.info } };
    case "SET_USER_PROFILE":
      return { ...state, userProfileCache: { ...state.userProfileCache, [action.userId]: action.info } };
    case "REPLACE_DOMAIN_STATE":
      return {
        ...state,
        userId: action.state.userId,
        activeConversationId: action.state.activeConversationId,
        conversations: action.state.conversations,
        messages: action.state.messages,
        systemMessages: action.state.systemMessages,
        systemUnreadCount: action.state.systemUnreadCount,
        latestSystemMessage: action.state.latestSystemMessage,
      };
    default:
      return state;
  }
}

// ========== Context ==========

interface StoreContextType {
  state: State;
  dispatch: React.Dispatch<Action>;
  login: (userId: string, password?: string) => Promise<void>;
  register: (params: { password?: string; nickname?: string; faceUrl?: string }) => Promise<string>;
  logout: () => void;
  sendMessage: (toUserId: string, content: string) => Promise<Message | undefined>;
  fetchConversations: () => Promise<void>;
  fetchFriends: () => Promise<void>;
  searchUser: (keyword: string, limit?: number) => Promise<void>;
  applyFriend: (targetUserId: string, reqMsg?: string) => Promise<void>;
  removeFriend: (targetUserId: string) => Promise<void>;
  searchGroup: (keyword: string, limit?: number) => Promise<void>;
  joinGroup: (groupId: string, reqMsg?: string) => Promise<GroupJoinResponse>;
  quitGroup: (groupId: string) => Promise<void>;
  fetchMyGroups: () => Promise<void>;
  approveFriend: (fromUserId: string, agreed: boolean) => Promise<void>;
  fetchUnhandledApplyCount: () => Promise<void>;
  approveGroupApply: (groupId: string, userId: string, agreed: boolean) => Promise<void>;
  fetchUnhandledGroupApplyCount: () => Promise<void>;
  fetchGroupMembers: (groupId: string) => Promise<void>;
  fetchGroupInfo: (groupId: string) => Promise<void>;
  fetchUserProfile: (userId: string) => Promise<void>;
  markConversationRead: (conversationId: string, seq?: number) => Promise<void>;
  refreshSystemMessages: () => Promise<void>;
  openSingleChat: (input: OpenSingleChatInput) => void;
  openGroupChat: (input: OpenGroupChatInput) => void;
  removeConversationLocal: (conversationId: string) => void;
  refreshAfterMembershipChanged: () => Promise<void>;
}

const StoreContext = createContext<StoreContextType | null>(null);

export interface OpenSingleChatInput {
  userId: string;
  nickname?: string;
  faceUrl?: string;
}

export interface OpenGroupChatInput {
  groupId: string;
  groupName?: string;
  faceUrl?: string;
}

function persistTokens(tokens: TokenPair) {
  if (tokens.token) localStorage.setItem(AUTH_TOKEN_KEY, tokens.token);
  if (tokens.refreshToken) localStorage.setItem(AUTH_REFRESH_TOKEN_KEY, tokens.refreshToken);
}

function clearStoredAuth() {
  localStorage.removeItem(AUTH_USER_ID_KEY);
  localStorage.removeItem(AUTH_TOKEN_KEY);
  localStorage.removeItem(AUTH_REFRESH_TOKEN_KEY);
  im.clearTokens();
}

function persistSyncCursors(messages: Record<string, Message[]>): void {
  const cursors = Object.entries(messages)
    .map(([conversationId, list]) => ({
      conversationId,
      lastSeq: list.reduce((max, msg) => Math.max(max, msg.seq || 0), 0),
    }))
    .filter((cursor) => cursor.lastSeq > 0);
  sessionStorage.setItem(SYNC_CURSORS_KEY, JSON.stringify(cursors));
}

function groupInfoFromConversation(list: Conversation[]): GroupInfo[] {
  return list
    .filter((c) => c.conversationType === ConversationType.GROUP)
    .map((c) => ({
      groupId: c.groupId || c.conversationId.replace(/^group_/, ""),
      groupName: c.groupName || c.showName,
      faceUrl: c.faceUrl,
    }));
}

function singleConversationId(currentUserId: string, friendUserId: string): string {
  return [currentUserId, friendUserId].sort().join("_");
}

function groupConversationId(groupId: string): string {
  return `group_${groupId}`;
}

function createSingleConversation(currentUserId: string, input: OpenSingleChatInput): Conversation {
  return normalizeConversation({
    conversationId: singleConversationId(currentUserId, input.userId),
    ownerUserId: currentUserId,
    conversationType: ConversationType.SINGLE,
    userId: input.userId,
    showName: input.nickname || input.userId,
    faceUrl: input.faceUrl,
    latestMsg: "",
    latestMsgSendTime: 0,
    unreadCount: 0,
    recvMsgOpt: MessageReceiveOption.NORMAL,
    isPinned: false,
  });
}

function createGroupConversation(currentUserId: string, input: OpenGroupChatInput): Conversation {
  return normalizeConversation({
    conversationId: groupConversationId(input.groupId),
    ownerUserId: currentUserId,
    conversationType: ConversationType.GROUP,
    groupId: input.groupId,
    groupName: input.groupName || input.groupId,
    showName: input.groupName || input.groupId,
    faceUrl: input.faceUrl,
    latestMsg: "",
    latestMsgSendTime: 0,
    unreadCount: 0,
    recvMsgOpt: MessageReceiveOption.NORMAL,
    isPinned: false,
  });
}

// ========== Provider ==========

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
    const conversation = currentState.conversations.find((c) => c.conversationId === conversationId);
    if (!conversation) {
      return;
    }
    const nextSeq = seq ?? (currentState.messages[conversationId] || []).reduce(
      (max, msg) => Math.max(max, msg.seq || 0),
      0,
    );
    if (nextSeq <= 0) {
      dispatch({ type: "MARK_READ_LOCAL", conversationId });
      return;
    }
    const lastSeq = lastReadSeqRef.current[conversationId] ?? -1;
    if (nextSeq <= lastSeq && conversation.unreadCount === 0) {
      return;
    }
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
        im.system.messages({ limit: 30 }),
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
    refreshTimerRef.current = window.setTimeout(flushRefreshTasks, 80);
  }, [flushRefreshTasks]);

  const fetchUserProfile = useCallback(async (userId: string) => {
    try {
      const info = userId === (localStorage.getItem(AUTH_USER_ID_KEY) || state.userId)
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

  // ── SDK 事件监听 ──
  useEffect(() => {
    const unsubConnection = im.on("connectionStateChanged", (s) => {
      dispatch({ type: "SET_CONNECTED", connected: s === "connected" });
      if (s === "connected" && localStorage.getItem(AUTH_TOKEN_KEY)) {
        void hydrateAfterAuth();
      }
    });

    const unsubMessageBatch = im.on("messageBatch", (sdkMsgs) => {
      const currentState = stateRef.current;
      const activeConversationId = currentState.activeConversationId;
      const currentUserId = localStorage.getItem(AUTH_USER_ID_KEY) || currentState.userId;
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

    const unsubFriendRequest = im.on("friendRequest", (apply: SDKFriendApply) => {
      const result = applyDomainEvent(stateRef.current, { type: "FRIEND_APPLY_UPDATED", apply });
      dispatch({ type: "REPLACE_DOMAIN_STATE", state: result.state });
      runRefreshTasks(result.refreshTasks);
      window.dispatchEvent(new CustomEvent(FRIEND_APPLY_UPDATED_EVENT, { detail: apply }));
    });

    const unsubGroupApply = im.on("groupApply", (apply: SDKGroupApply) => {
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
      dispatch({ type: "SET_TOKENS", token: tokens.token, refreshToken: tokens.refreshToken });
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
  }, [hydrateAfterAuth, markConversationRead, runRefreshTasks]);

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
    persistSyncCursors(state.messages);
  }, [state.messages]);

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

  // ── Actions ──

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
    clearStoredAuth();
    im.disconnect();
    dispatch({ type: "LOGOUT" });
  }, []);

  const sendMessage = useCallback(async (toUserId: string, content: string) => {
    const messageContent = { text: content };
    const clientMsgId = createClientMsgId();
    await im.waitConnected();
    const ack = await im.message.send({ toUserId, contentType: "text", content: messageContent, clientMsgId });
    const currentUserId = localStorage.getItem(AUTH_USER_ID_KEY) || state.userId || "";
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

  const searchUser = useCallback(async (keyword: string, limit = 20) => {
    const list = await im.friend.search(keyword, limit);
    dispatch({ type: "SET_SEARCH_USERS", list: list as unknown as UserInfo[] });
  }, []);

  const searchGroup = useCallback(async (keyword: string, limit = 20) => {
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

  const fetchGroupMembers = useCallback(async (groupId: string) => {
    try {
      const members = await im.group.members(groupId);
      dispatch({ type: "SET_GROUP_MEMBERS", groupId, members: members as unknown as GroupMember[] });
    } catch (err) {
      console.error("fetchGroupMembers failed:", err);
    }
  }, []);

  const fetchGroupInfo = useCallback(async (groupId: string) => {
    try {
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

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
  type ReactNode,
} from "react";
import { im } from "@/sdk/im-sdk";
import type { Message as SDKMessage, TokenPair } from "im-sdk";

// ========== 类型（与 SDK 类型一致） ==========

export interface Conversation {
  conversationId: string;
  ownerUserId: string;
  conversationType: number;
  userId?: string;
  groupId?: string;
  groupName?: string;
  showName: string;
  faceUrl?: string;
  latestMsg?: string;
  latestMsgSendTime?: number;
  unreadCount: number;
  recvMsgOpt: number;
  isPinned: boolean;
}

export interface Message {
  messageId: string;
  seq: number;
  senderUserId: string;
  senderNickname?: string;
  conversationId: string;
  contentType: number;
  content: string;
  createTime: number;
  status: number;
}

export interface UserInfo {
  userId: string;
  nickname?: string;
  faceUrl?: string;
  appMangerLevel?: number;
}

export interface GroupInfo {
  groupId: string;
  groupName: string;
  faceUrl?: string;
  ownerUserId?: string;
  memberCount?: number;
  groupType?: number;
  needVerification?: number;
  createTime?: number;
}

export interface FriendInfo {
  ownerUserId: string;
  friendUserId: string;
  nickname?: string;
  faceUrl?: string;
  remark?: string;
  addSource: number;
  isPinned: boolean;
  createTime: number;
}

export interface GroupMember {
  groupId: string;
  userId: string;
  nickname?: string;
  faceUrl?: string;
  roleLevel: number;
  joinTime: number;
}

// ========== State ==========

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

  activeConversationId: string | null;
  groupMembers: Record<string, GroupMember[]>;
  groupInfoCache: Record<string, GroupInfo>;
  userProfileCache: Record<string, UserInfo>;
}

const initialState: State = {
  token: localStorage.getItem("im_token"),
  refreshToken: localStorage.getItem("im_refreshToken"),
  userId: localStorage.getItem("im_userId"),
  connected: false,
  conversations: [],
  messages: {},
  friends: [],
  myGroups: [],
  searchUsers: [],
  searchGroups: [],
  unhandledApplyCount: 0,
  activeConversationId: null,
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
  | { type: "SET_ACTIVE_CONVERSATION"; conversationId: string | null }
  | { type: "ADD_FRIEND"; friend: FriendInfo }
  | { type: "REMOVE_FRIEND"; friendUserId: string }
  | { type: "ADD_CONVERSATION"; conversation: Conversation }
  | { type: "UPDATE_CONVERSATION_LATEST"; conversationId: string; latestMsg: string; latestMsgSendTime: number; incoming?: boolean }
  | { type: "SET_GROUP_MEMBERS"; groupId: string; members: GroupMember[] }
  | { type: "SET_GROUP_INFO"; groupId: string; info: GroupInfo }
  | { type: "SET_USER_PROFILE"; userId: string; info: UserInfo };

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
    case "SET_CONVERSATIONS":
      return { ...state, conversations: action.list };
    case "APPEND_MESSAGE": {
      const existing = state.messages[action.conversationId] || [];
      if (existing.some((m) => m.messageId === action.msg.messageId || (m.seq > 0 && m.seq === action.msg.seq))) return state;
      return {
        ...state,
        messages: { ...state.messages, [action.conversationId]: [...existing, action.msg] },
      };
    }
    case "ADD_MESSAGES": {
      const existing = state.messages[action.conversationId] || [];
      const newMsgs = action.msgs.filter(
        (m) => !existing.some((e) => e.messageId === m.messageId || (e.seq > 0 && e.seq === m.seq))
      );
      if (newMsgs.length === 0) return state;
      return {
        ...state,
        messages: {
          ...state.messages,
          [action.conversationId]: [...existing, ...newMsgs].sort((a, b) => a.seq - b.seq || a.createTime - b.createTime),
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
    case "SET_ACTIVE_CONVERSATION": {
      const cleared = action.conversationId
        ? state.conversations.map((c) =>
            c.conversationId === action.conversationId ? { ...c, unreadCount: 0 } : c
          )
        : state.conversations;
      return { ...state, activeConversationId: action.conversationId, conversations: cleared };
    }
    case "ADD_FRIEND": {
      if (state.friends.some((f) => f.friendUserId === action.friend.friendUserId)) return state;
      return { ...state, friends: [...state.friends, action.friend] };
    }
    case "REMOVE_FRIEND":
      return { ...state, friends: state.friends.filter((f) => f.friendUserId !== action.friendUserId) };
    case "ADD_CONVERSATION": {
      if (state.conversations.some((c) => c.conversationId === action.conversation.conversationId)) return state;
      return { ...state, conversations: [...state.conversations, action.conversation] };
    }
    case "UPDATE_CONVERSATION_LATEST": {
      const convExists = state.conversations.some((c) => c.conversationId === action.conversationId);
      if (!convExists) return state;
      const isActive = state.activeConversationId === action.conversationId;
      return {
        ...state,
        conversations: state.conversations.map((c) =>
          c.conversationId === action.conversationId
            ? {
                ...c,
                latestMsg: action.latestMsg,
                latestMsgSendTime: action.latestMsgSendTime,
                unreadCount: action.incoming && !isActive ? c.unreadCount + 1 : c.unreadCount,
              }
            : c
        ),
      };
    }
    case "SET_GROUP_MEMBERS":
      return { ...state, groupMembers: { ...state.groupMembers, [action.groupId]: action.members } };
    case "SET_GROUP_INFO":
      return { ...state, groupInfoCache: { ...state.groupInfoCache, [action.groupId]: action.info } };
    case "SET_USER_PROFILE":
      return { ...state, userProfileCache: { ...state.userProfileCache, [action.userId]: action.info } };
    default:
      return state;
  }
}

// ========== Context ==========

interface StoreContextType {
  state: State;
  dispatch: React.Dispatch<Action>;
  login: (userId: string, password?: string) => Promise<void>;
  register: (userId: string, password?: string) => Promise<void>;
  logout: () => void;
  sendMessage: (toUserId: string, content: string) => Promise<Message | undefined>;
  fetchConversations: () => Promise<void>;
  fetchFriends: () => Promise<void>;
  searchUser: (keyword: string, limit?: number) => void;
  applyFriend: (targetUserId: string, reqMsg?: string) => void;
  removeFriend: (targetUserId: string) => void;
  searchGroup: (keyword: string, limit?: number) => void;
  joinGroup: (groupId: string, reqMsg?: string) => void;
  quitGroup: (groupId: string) => void;
  fetchMyGroups: () => Promise<void>;
  approveFriend: (fromUserId: string, agreed: boolean) => Promise<void>;
  fetchUnhandledApplyCount: () => Promise<void>;
  fetchGroupMembers: (groupId: string) => Promise<void>;
  fetchGroupInfo: (groupId: string) => Promise<void>;
  fetchUserProfile: (userId: string) => Promise<void>;
}

const StoreContext = createContext<StoreContextType | null>(null);

function persistTokens(tokens: TokenPair) {
  if (tokens.token) localStorage.setItem("im_token", tokens.token);
  if (tokens.refreshToken) localStorage.setItem("im_refreshToken", tokens.refreshToken);
}

function toViewMessage(sdkMsg: SDKMessage): Message {
  return {
    messageId: sdkMsg.messageId,
    seq: sdkMsg.messageSeq ?? sdkMsg.sequenceId ?? 0,
    senderUserId: sdkMsg.fromUserId,
    senderNickname: undefined,
    conversationId: sdkMsg.conversationId,
    contentType: Number(sdkMsg.contentType),
    content: sdkMsg.content,
    createTime: sdkMsg.timestamp,
    status: sdkMsg.status,
  };
}

function groupInfoFromConversation(list: Conversation[]): GroupInfo[] {
  return list
    .filter((c) => c.conversationType === 2)
    .map((c) => ({
      groupId: c.groupId || c.conversationId.replace(/^group_/, ""),
      groupName: c.groupName || c.showName,
      faceUrl: c.faceUrl,
    }));
}

// ========== Provider ==========

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  const fetchConversations = useCallback(async () => {
    try {
      const list = (await im.conversation.list()) as unknown as Conversation[];
      dispatch({ type: "SET_CONVERSATIONS", list });
    } catch (err) {
      console.error("fetchConversations failed:", err);
    }
  }, []);

  const fetchMyGroups = useCallback(async () => {
    try {
      const groups = await im.group.list();
      dispatch({ type: "SET_MY_GROUPS", list: groups as unknown as GroupInfo[] });
    } catch (err) {
      console.error("fetchMyGroups failed:", err);
      try {
        const conversations = (await im.conversation.list()) as unknown as Conversation[];
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

  const hydrateAfterAuth = useCallback(async () => {
    await Promise.all([fetchConversations(), fetchFriends(), fetchMyGroups(), fetchUnhandledApplyCount()]);
  }, [fetchConversations, fetchFriends, fetchMyGroups, fetchUnhandledApplyCount]);

  // ── SDK 事件监听 ──
  useEffect(() => {
    const unsubConnection = im.on("connectionStateChanged", (s) => {
      dispatch({ type: "SET_CONNECTED", connected: s === "connected" });
      if (s === "connected" && localStorage.getItem("im_token")) {
        void hydrateAfterAuth();
      }
    });

    const unsubMessage = im.on("message", (sdkMsg) => {
      const msg = toViewMessage(sdkMsg);
      if (!msg.conversationId) return;
      dispatch({ type: "APPEND_MESSAGE", conversationId: msg.conversationId, msg });
      dispatch({
        type: "UPDATE_CONVERSATION_LATEST",
        conversationId: msg.conversationId,
        latestMsg: msg.content,
        latestMsgSendTime: msg.createTime,
        incoming: msg.senderUserId !== localStorage.getItem("im_userId"),
      });
    });

    const unsubRevoke = im.on("messageRevoked", (event) => {
      dispatch({ type: "REVOKE_MESSAGE", conversationId: event.conversationId, seq: event.seq });
      dispatch({
        type: "UPDATE_CONVERSATION_LATEST",
        conversationId: event.conversationId,
        latestMsg: "消息已撤回",
        latestMsgSendTime: Date.now(),
      });
    });

    const unsubFriendRequest = im.on("friendRequest", () => {
      void fetchUnhandledApplyCount();
    });

    const unsubTokenChanged = im.on("tokenChanged", (tokens) => {
      persistTokens(tokens);
      dispatch({ type: "SET_TOKENS", token: tokens.token, refreshToken: tokens.refreshToken });
    });

    return () => {
      unsubConnection();
      unsubMessage();
      unsubRevoke();
      unsubFriendRequest();
      unsubTokenChanged();
    };
  }, [fetchUnhandledApplyCount, hydrateAfterAuth]);

  useEffect(() => {
    if (state.token && state.userId && im.state === "disconnected") {
      im.connect();
    }
  }, [state.token, state.userId]);

  // ── Actions ──

  const login = useCallback(async (userId: string, password?: string) => {
    localStorage.setItem("im_userId", userId);
    const tokens = await im.login(userId, password);
    persistTokens(tokens);
    dispatch({ type: "SET_AUTH", userId, token: tokens.token ?? "", refreshToken: tokens.refreshToken });
    await hydrateAfterAuth();
  }, [hydrateAfterAuth]);

  const register = useCallback(async (userId: string, password?: string) => {
    localStorage.setItem("im_userId", userId);
    await im.user.register(userId, password);
    await login(userId, password);
  }, [login]);

  const logout = useCallback(() => {
    localStorage.removeItem("im_userId");
    localStorage.removeItem("im_token");
    localStorage.removeItem("im_refreshToken");
    im.disconnect();
    dispatch({ type: "LOGOUT" });
  }, []);

  const sendMessage = useCallback(async (toUserId: string, content: string) => {
    const sdkMsg = await im.message.send({ toUserId, contentType: "1", content });
    const msg = toViewMessage(sdkMsg);
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
  }, [fetchConversations]);

  const searchUser = useCallback(async (keyword: string, limit = 20) => {
    try {
      const list = await im.friend.search(keyword, limit);
      dispatch({ type: "SET_SEARCH_USERS", list: list as unknown as UserInfo[] });
    } catch (err) {
      console.error("searchUser failed:", err);
    }
  }, []);

  const searchGroup = useCallback(async (keyword: string, limit = 20) => {
    try {
      const list = await im.group.search(keyword, limit);
      dispatch({ type: "SET_SEARCH_GROUPS", list: list as unknown as GroupInfo[] });
    } catch (err) {
      console.error("searchGroup failed:", err);
    }
  }, []);

  const applyFriend = useCallback(async (targetUserId: string, reqMsg?: string) => {
    try {
      await im.friend.apply(targetUserId, reqMsg);
      setTimeout(() => void fetchFriends(), 300);
    } catch (err) {
      console.error("applyFriend failed:", err);
    }
  }, [fetchFriends]);

  const removeFriend = useCallback(async (targetUserId: string) => {
    try {
      await im.friend.remove(targetUserId);
      dispatch({ type: "REMOVE_FRIEND", friendUserId: targetUserId });
    } catch (err) {
      console.error("removeFriend failed:", err);
    }
  }, []);

  const joinGroup = useCallback(async (groupId: string, reqMsg?: string) => {
    try {
      await im.group.join(groupId, reqMsg);
      setTimeout(() => void fetchConversations(), 300);
    } catch (err) {
      console.error("joinGroup failed:", err);
    }
  }, [fetchConversations]);

  const quitGroup = useCallback(async (groupId: string) => {
    try {
      await im.group.quit(groupId);
      setTimeout(() => void fetchConversations(), 300);
    } catch (err) {
      console.error("quitGroup failed:", err);
    }
  }, [fetchConversations]);

  const approveFriend = useCallback(async (fromUserId: string, agreed: boolean) => {
    try {
      await im.friend.approve(fromUserId, agreed);
      await Promise.all([fetchFriends(), fetchUnhandledApplyCount()]);
    } catch (err) {
      console.error("approveFriend failed:", err);
    }
  }, [fetchFriends, fetchUnhandledApplyCount]);

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

  const fetchUserProfile = useCallback(async (userId: string) => {
    try {
      const info = await im.user.info(userId);
      dispatch({ type: "SET_USER_PROFILE", userId, info: info as unknown as UserInfo });
    } catch (err) {
      console.error("fetchUserProfile failed:", err);
    }
  }, []);

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
        fetchGroupMembers,
        fetchGroupInfo,
        fetchUserProfile,
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

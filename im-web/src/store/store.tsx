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
  token: null,
  userId: null,
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
  | { type: "SET_AUTH"; userId: string; token: string }
  | { type: "LOGOUT" }
  | { type: "SET_CONVERSATIONS"; list: Conversation[] }
  | { type: "APPEND_MESSAGE"; conversationId: string; msg: Message }
  | { type: "ADD_MESSAGES"; conversationId: string; msgs: Message[] }
  | { type: "SET_FRIENDS"; list: FriendInfo[] }
  | { type: "SET_MY_GROUPS"; list: GroupInfo[] }
  | { type: "SET_SEARCH_USERS"; list: UserInfo[] }
  | { type: "SET_SEARCH_GROUPS"; list: GroupInfo[] }
  | { type: "SET_UNHANDLED_APPLY_COUNT"; count: number }
  | { type: "SET_ACTIVE_CONVERSATION"; conversationId: string | null }
  | { type: "ADD_FRIEND"; friend: FriendInfo }
  | { type: "REMOVE_FRIEND"; friendUserId: string }
  | { type: "ADD_CONVERSATION"; conversation: Conversation }
  | { type: "UPDATE_CONVERSATION_LATEST"; conversationId: string; latestMsg: string; latestMsgSendTime: number }
  | { type: "SET_GROUP_MEMBERS"; groupId: string; members: GroupMember[] }
  | { type: "SET_GROUP_INFO"; groupId: string; info: GroupInfo }
  | { type: "SET_USER_PROFILE"; userId: string; info: UserInfo };

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case "SET_CONNECTED":
      return { ...state, connected: action.connected };
    case "SET_AUTH":
      return { ...state, userId: action.userId, token: action.token };
    case "LOGOUT":
      return { ...initialState };
    case "SET_CONVERSATIONS":
      return { ...state, conversations: action.list };
    case "APPEND_MESSAGE": {
      const existing = state.messages[action.conversationId] || [];
      if (existing.some((m) => m.messageId === action.msg.messageId)) return state;
      return {
        ...state,
        messages: { ...state.messages, [action.conversationId]: [...existing, action.msg] },
      };
    }
    case "ADD_MESSAGES": {
      const existing = state.messages[action.conversationId] || [];
      const newMsgs = action.msgs.filter(
        (m) => !existing.some((e) => e.messageId === m.messageId)
      );
      if (newMsgs.length === 0) return state;
      return {
        ...state,
        messages: { ...state.messages, [action.conversationId]: [...existing, ...newMsgs] },
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
      if (state.friends.some((f) => f.friendUserId === action.friend.friendUserId))
        return state;
      return { ...state, friends: [...state.friends, action.friend] };
    }
    case "REMOVE_FRIEND":
      return { ...state, friends: state.friends.filter((f) => f.friendUserId !== action.friendUserId) };
    case "ADD_CONVERSATION": {
      if (state.conversations.some((c) => c.conversationId === action.conversation.conversationId))
        return state;
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
                // 如果是当前活跃会话，不增加未读数
                unreadCount: isActive ? c.unreadCount : c.unreadCount + 1,
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
  login: (userId: string, password?: string) => void;
  register: (userId: string, password?: string) => void;
  logout: () => void;
  sendMessage: (toUserId: string, content: string) => Promise<void>;
  fetchConversations: () => void;
  fetchFriends: () => void;
  searchUser: (keyword: string, limit?: number) => void;
  applyFriend: (targetUserId: string, reqMsg?: string) => void;
  removeFriend: (targetUserId: string) => void;
  searchGroup: (keyword: string, limit?: number) => void;
  joinGroup: (groupId: string, reqMsg?: string) => void;
  quitGroup: (groupId: string) => void;
  fetchMyGroups: () => void;
  approveFriend: (fromUserId: string, agreed: boolean) => Promise<void>;
  fetchUnhandledApplyCount: () => void;
  fetchGroupMembers: (groupId: string) => Promise<void>;
  fetchGroupInfo: (groupId: string) => Promise<void>;
  fetchUserProfile: (userId: string) => Promise<void>;
}

const StoreContext = createContext<StoreContextType | null>(null);

// ========== Provider ==========

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  // ── SDK 事件监听 ──
  useEffect(() => {
    const unsub1 = im.on("connectionStateChanged", (s) => {
      dispatch({ type: "SET_CONNECTED", connected: s === "connected" });

      // 连接成功后，如果有缓存的 token 但未设置 userId，自动重新登录
      if (s === "connected") {
        const cachedToken = localStorage.getItem("im_token");
        const cachedUserId = localStorage.getItem("im_userId");
        if (!cachedToken && cachedUserId) {
          im.user.login(cachedUserId).then((resp) => {
            const token = resp.data as string;
            localStorage.setItem("im_token", token);
            localStorage.setItem("im_userId", cachedUserId);
            dispatch({ type: "SET_AUTH", userId: cachedUserId, token });
          });
        }
      }
    });

    const unsub2 = im.on("message", (sdkMsg) => {
      const msg: Message = {
        messageId: sdkMsg.messageId,
        seq: sdkMsg.messageSeq,
        senderUserId: sdkMsg.fromUserId,
        senderNickname: undefined,
        conversationId: sdkMsg.conversationId,
        contentType: sdkMsg.contentType,
        content: sdkMsg.content,
        createTime: sdkMsg.timestamp,
        status: 1,
      };
      const convId = msg.conversationId;
      if (convId) {
        dispatch({ type: "APPEND_MESSAGE", conversationId: convId, msg });
        // 更新会话最新消息和未读数
        dispatch({
          type: "UPDATE_CONVERSATION_LATEST",
          conversationId: convId,
          latestMsg: msg.content,
          latestMsgSendTime: msg.createTime,
        });
      }
    });

    const unsub3 = im.on("friendRequest", () => {
      // 收到好友申请推送，刷新未处理数
      im.friend.unhandledApplyCount().then((count) => {
        dispatch({ type: "SET_UNHANDLED_APPLY_COUNT", count });
      });
    });

    return () => {
      unsub1();
      unsub2();
      unsub3();
    };
  }, []);

  // ── Actions ──

  const fetchConversations = useCallback(async () => {
    try {
      const list = await im.conversation.list();
      dispatch({ type: "SET_CONVERSATIONS", list: list as unknown as Conversation[] });
      const groupsList = (list as unknown as Conversation[])
        .filter((c) => c.conversationType === 2)
        .map((c) => ({
          groupId: c.groupId || c.conversationId,
          groupName: c.groupName || c.showName,
          faceUrl: c.faceUrl,
        }));
      dispatch({ type: "SET_MY_GROUPS", list: groupsList });
    } catch (err) {
      console.error("fetchConversations failed:", err);
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

  const login = useCallback(async (userId: string, password?: string) => {
    localStorage.setItem("im_userId", userId);
    try {
      const resp = await im.user.login(userId, password);
      const token = resp.data as string;
      localStorage.setItem("im_token", token);
      dispatch({ type: "SET_AUTH", userId, token });
      // 登录后拉取数据
      await Promise.all([fetchConversations(), fetchFriends()]);
      // 拉取未处理好友申请数
      im.friend.unhandledApplyCount().then((count) => {
        dispatch({ type: "SET_UNHANDLED_APPLY_COUNT", count });
      });
    } catch (err: unknown) {
      console.error("Login failed:", err);
    }
  }, [fetchConversations, fetchFriends]);

  const register = useCallback(async (userId: string, password?: string) => {
    localStorage.setItem("im_userId", userId);
    try {
      await im.user.register(userId, password);
      // 注册成功后自动登录
      await login(userId, password);
    } catch (err: unknown) {
      console.error("Register failed:", err);
    }
  }, [login]);

  const logout = useCallback(() => {
    localStorage.removeItem("im_userId");
    localStorage.removeItem("im_token");
    im.disconnect();
    dispatch({ type: "LOGOUT" });
  }, []);

  const sendMessage = useCallback(async (toUserId: string, content: string) => {
    await im.message.send({ toUserId, contentType: "1", content });
  }, []);

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
      // 申请成功后刷新好友列表
      setTimeout(() => fetchFriends(), 300);
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
      setTimeout(() => fetchConversations(), 300);
    } catch (err) {
      console.error("joinGroup failed:", err);
    }
  }, [fetchConversations]);

  const quitGroup = useCallback(async (groupId: string) => {
    try {
      await im.group.quit(groupId);
      setTimeout(() => fetchConversations(), 300);
    } catch (err) {
      console.error("quitGroup failed:", err);
    }
  }, [fetchConversations]);

  const fetchUnhandledApplyCount = useCallback(async () => {
    try {
      const count = await im.friend.unhandledApplyCount();
      dispatch({ type: "SET_UNHANDLED_APPLY_COUNT", count });
    } catch (err) {
      console.error("fetchUnhandledApplyCount failed:", err);
    }
  }, []);

  const approveFriend = useCallback(async (fromUserId: string, agreed: boolean) => {
    try {
      await im.friend.approve(fromUserId, agreed);
      // 审批后刷新好友列表和未处理数
      await Promise.all([fetchFriends(), fetchUnhandledApplyCount()]);
    } catch (err) {
      console.error("approveFriend failed:", err);
    }
  }, [fetchFriends, fetchUnhandledApplyCount]);

  const fetchMyGroups = useCallback(() => {
    fetchConversations();
  }, [fetchConversations]);

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

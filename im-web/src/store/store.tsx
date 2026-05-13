/**
 * IM 应用状态管理。
 *
 * 基于 React Context + useReducer 的轻量状态管理，避免引入外部状态库。
 */

import React, { createContext, useContext, useReducer, useCallback, type ReactNode } from "react";
import { type IMHeader, CMD, cmdName } from "@/protocol/protocol";
import { imConnection } from "@/protocol/connection";

// ========== 类型 ==========

export interface Conversation {
  conversationId: string;
  ownerUserId: string;
  conversationType: number; // 1=单聊, 2=群聊
  userId?: string;         // 对方 userId（单聊）
  groupId?: string;        // 群 ID（群聊）
  showName: string;
  faceUrl?: string;
  latestMsg?: string;
  latestMsgSendTime?: number;
  unreadCount: number;
  recvMsgOpt: number;      // 0=正常, 1=屏蔽, 2=只接收不提醒
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
  status: number; // 0=发送中, 1=已送达, 2=已读
}

export interface UserInfo {
  userId: string;
  nickname?: string;
  faceUrl?: string;
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

// ========== State ==========

interface State {
  // 认证
  token: string | null;
  userId: string | null;

  // 数据
  conversations: Conversation[];
  messages: Record<string, Message[]>; // conversationId → messages
  friends: FriendInfo[];
  users: Record<string, UserInfo>;

  // UI
  activeConversationId: string | null;
  connected: boolean;
}

const initialState: State = {
  token: null,
  userId: null,
  conversations: [],
  messages: {},
  friends: [],
  users: {},
  activeConversationId: null,
  connected: false,
};

// ========== Actions ==========

type Action =
  | { type: "SET_CONNECTED"; connected: boolean }
  | { type: "SET_AUTH"; userId: string; token: string }
  | { type: "LOGOUT" }
  | { type: "SET_CONVERSATIONS"; list: Conversation[] }
  | { type: "APPEND_MESSAGE"; conversationId: string; msg: Message }
  | { type: "SET_FRIENDS"; list: FriendInfo[] }
  | { type: "SET_ACTIVE_CONVERSATION"; conversationId: string | null }
  | { type: "SET_USERS"; users: Record<string, UserInfo> };

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
      // 去重
      if (existing.some((m) => m.messageId === action.msg.messageId)) return state;
      return {
        ...state,
        messages: {
          ...state.messages,
          [action.conversationId]: [...existing, action.msg],
        },
      };
    }

    case "SET_FRIENDS":
      return { ...state, friends: action.list };

    case "SET_ACTIVE_CONVERSATION":
      return { ...state, activeConversationId: action.conversationId };

    case "SET_USERS":
      return { ...state, users: { ...state.users, ...action.users } };

    default:
      return state;
  }
}

// ========== Context ==========

interface StoreContextType {
  state: State;
  dispatch: React.Dispatch<Action>;
  login: (userId: string) => void;
  logout: () => void;
  sendMessage: (toUserId: string, content: string) => void;
  fetchConversations: () => void;
  fetchFriends: () => void;
}

const StoreContext = createContext<StoreContextType | null>(null);

// ========== Provider ==========

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  const login = useCallback((userId: string) => {
    localStorage.setItem("im_userId", userId);
    imConnection.login(userId);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem("im_userId");
    localStorage.removeItem("im_token");
    imConnection.disconnect();
    dispatch({ type: "LOGOUT" });
  }, []);

  const sendMessage = useCallback(
    (toUserId: string, content: string) => {
      imConnection.sendMessage(toUserId, content);
    },
    []
  );

  const fetchConversations = useCallback(() => {
    imConnection.fetchConversations();
  }, []);

  const fetchFriends = useCallback(() => {
    imConnection.fetchFriendList();
  }, []);

  return (
    <StoreContext.Provider
      value={{ state, dispatch, login, logout, sendMessage, fetchConversations, fetchFriends }}
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

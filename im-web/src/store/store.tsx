/**
 * IM 应用状态管理。
 *
 * 基于 React Context + useReducer。
 * 全局消息监听统一在 StoreProvider 中注册。
 */

import React, {
  createContext,
  useContext,
  useReducer,
  useCallback,
  useEffect,
  type ReactNode,
} from "react";
import { type IMHeader, CMD, cmdName } from "@/protocol/protocol";
import { imConnection } from "@/protocol/connection";
import { toast } from "sonner";

// ========== 类型 ==========

export interface Conversation {
  conversationId: string;
  ownerUserId: string;
  conversationType: number; // 1=单聊, 2=群聊
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

// ========== State ==========

interface State {
  token: string | null;
  userId: string | null;
  connected: boolean;

  conversations: Conversation[];
  messages: Record<string, Message[]>;
  friends: FriendInfo[];

  myGroups: GroupInfo[];       // 我加入的群组
  searchUsers: UserInfo[];     // 用户搜索结果
  searchGroups: GroupInfo[];   // 群组搜索结果

  activeConversationId: string | null;
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
  activeConversationId: null,
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
  | { type: "SET_ACTIVE_CONVERSATION"; conversationId: string | null }
  | { type: "ADD_FRIEND"; friend: FriendInfo }
  | { type: "REMOVE_FRIEND"; friendUserId: string }
  | { type: "ADD_CONVERSATION"; conversation: Conversation };

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
    case "SET_ACTIVE_CONVERSATION":
      return { ...state, activeConversationId: action.conversationId };
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
    default:
      return state;
  }
}

// ========== Context ==========

interface StoreContextType {
  state: State;
  dispatch: React.Dispatch<Action>;
  // 认证
  login: (userId: string, password?: string) => void;
  register: (userId: string, password?: string) => void;
  logout: () => void;
  // 聊天
  sendMessage: (toUserId: string, content: string) => void;
  fetchConversations: () => void;
  // 好友
  fetchFriends: () => void;
  searchUser: (keyword: string, limit?: number) => void;
  applyFriend: (targetUserId: string, reqMsg?: string) => void;
  removeFriend: (targetUserId: string) => void;
  // 群组
  searchGroup: (keyword: string, limit?: number) => void;
  joinGroup: (groupId: string, reqMsg?: string) => void;
  quitGroup: (groupId: string) => void;
  fetchMyGroups: () => void;
}

const StoreContext = createContext<StoreContextType | null>(null);

// ========== Provider ==========

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  // ====== 全局消息监听 ======
  useEffect(() => {
    const unsub = imConnection.on("message", (frame) => {
      if (!frame) return;
      const h = frame.header;
      const op = parseInt(h._op || "0");

      // LOGIN_ACK
      if (op === CMD.LOGIN_ACK && h.status === "OK" && h.token) {
        localStorage.setItem("im_token", h.token);
        dispatch({
          type: "SET_AUTH",
          userId: h.userId || localStorage.getItem("im_userId") || "",
          token: h.token,
        });
        setTimeout(() => {
          imConnection.fetchConversations();
          imConnection.fetchFriendList();
        }, 200);
        return;
      }

      // FRIEND_LIST_ACK
      if (op === CMD.FRIEND_LIST_ACK && h.status === "OK" && h.friends) {
        try {
          const list = JSON.parse(h.friends);
          dispatch({ type: "SET_FRIENDS", list });
        } catch (e) {
          console.warn("FRIEND_LIST_ACK parse error", e);
        }
        return;
      }

      // friend add/remove ack → refresh list
      if ((op === CMD.FRIEND_APPLY_ACK || op === CMD.FRIEND_REMOVE_ACK) && h.status === "OK") {
        setTimeout(() => imConnection.fetchFriendList(), 300);
        return;
      }

      // USER_SEARCH_ACK
      if (op === CMD.USER_SEARCH_ACK && h.status === "OK" && h.users) {
        try {
          const list = JSON.parse(h.users);
          dispatch({ type: "SET_SEARCH_USERS", list });
        } catch {}
        return;
      }

      // GROUP_SEARCH_ACK
      if (op === CMD.GROUP_SEARCH_ACK && h.status === "OK" && h.groups) {
        try {
          const list = JSON.parse(h.groups);
          dispatch({ type: "SET_SEARCH_GROUPS", list });
        } catch {}
        return;
      }

      // GROUP_JOIN_ACK / GROUP_QUIT_ACK → refresh conversations
      if ((op === CMD.GROUP_JOIN_ACK || op === CMD.GROUP_QUIT_ACK) && h.status === "OK") {
        setTimeout(() => imConnection.fetchConversations(), 300);
        return;
      }

      // CONVERSATION_GET_ACK
      if (op === CMD.CONVERSATION_GET_ACK && h.status === "OK" && h.list) {
        try {
          const list = JSON.parse(h.list);
          dispatch({ type: "SET_CONVERSATIONS", list });
          // 提取群组列表
          const groupsList = list
            .filter((c: any) => c.conversationType === 2)
            .map((c: any) => ({
              groupId: c.groupId,
              groupName: c.groupName || c.showName,
              faceUrl: c.faceUrl,
            }));
          dispatch({ type: "SET_MY_GROUPS", list: groupsList });
        } catch (e) {
          console.warn("parse conversations failed", e);
        }
        return;
      }

      // SINGLE_CHAT_ACK / 新消息
      if ((op === CMD.SINGLE_CHAT || op === CMD.SINGLE_CHAT_ACK) && h.messageId && h.content) {
        try {
          const msg: Message = {
            messageId: h.messageId || "",
            seq: parseInt(h._seq || "0"),
            senderUserId: h.fromUserId || "",
            senderNickname: h.senderNickname,
            conversationId: h.conversationId || "",
            contentType: parseInt(h.contentType || "1"),
            content: h.content || "",
            createTime: parseInt(h._ts || "0"),
            status: op === CMD.SINGLE_CHAT_ACK ? 1 : 0,
          };
          const convId = msg.conversationId;
          if (convId) {
            dispatch({ type: "APPEND_MESSAGE", conversationId: convId, msg });
          }
          // 不在当前 session 时 toast 提醒
          const uid = localStorage.getItem("im_userId");
          if (h.fromUserId && h.fromUserId !== uid) {
            toast(`${h.senderNickname || h.fromUserId}: ${h.content}`);
          }
        } catch {}
        return;
      }

      // FRIEND_APPLY 通知
      if (op === CMD.FRIEND_APPLY && h.fromUserId && h.fromUserId !== state.userId) {
        toast(`好友申请：${h.fromUserId}`);
        return;
      }
    });

    // 连接事件
    const unsubOpen = imConnection.on("open", () => {
      dispatch({ type: "SET_CONNECTED", connected: true });
      const cachedToken = localStorage.getItem("im_token");
      const cachedUserId = localStorage.getItem("im_userId");
      if (!cachedToken && cachedUserId) {
        imConnection.login(cachedUserId);
      }
    });
    const unsubClose = imConnection.on("close", () => {
      dispatch({ type: "SET_CONNECTED", connected: false });
    });

    return () => {
      unsub();
      unsubOpen();
      unsubClose();
    };
  }, [state.userId]);

  // ====== Actions ======

  const login = useCallback((userId: string, password?: string) => {
    localStorage.setItem("im_userId", userId);
    imConnection.login(userId, password);
  }, []);

  const register = useCallback((userId: string, password?: string) => {
    localStorage.setItem("im_userId", userId);
    imConnection.register(userId, password);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem("im_userId");
    localStorage.removeItem("im_token");
    imConnection.disconnect();
    dispatch({ type: "LOGOUT" });
  }, []);

  const sendMessage = useCallback((toUserId: string, content: string) => {
    imConnection.sendMessage(toUserId, content);
  }, []);

  const fetchConversations = useCallback(() => {
    imConnection.fetchConversations();
  }, []);

  const fetchFriends = useCallback(() => {
    imConnection.fetchFriendList();
  }, []);

  const searchUser = useCallback((keyword: string, limit = 20) => {
    imConnection.searchUser(keyword, limit);
  }, []);

  const searchGroup = useCallback((keyword: string, limit = 20) => {
    imConnection.searchGroup(keyword, limit);
  }, []);

  const applyFriend = useCallback((targetUserId: string, reqMsg?: string) => {
    imConnection.applyFriend(targetUserId, reqMsg);
  }, []);

  const removeFriend = useCallback((targetUserId: string) => {
    imConnection.removeFriend(targetUserId);
  }, []);

  const joinGroup = useCallback((groupId: string, reqMsg?: string) => {
    imConnection.joinGroup(groupId, reqMsg);
  }, []);

  const quitGroup = useCallback((groupId: string) => {
    imConnection.quitGroup(groupId);
  }, []);

  const fetchMyGroups = useCallback(() => {
    imConnection.fetchConversations();
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

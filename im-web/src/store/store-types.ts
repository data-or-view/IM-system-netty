import type { Dispatch } from "react";
import type {
  Conversation as SDKConversation,
  FriendApply as SDKFriendApply,
  FriendInfo as SDKFriendInfo,
  GroupApply as SDKGroupApply,
  GroupInfo as SDKGroupInfo,
  GroupJoinResponse,
  GroupMember as SDKGroupMember,
  SystemMessageInboxItem,
  SystemMessageSummary,
  UserInfo as SDKUserInfo,
} from "im-sdk";
import type { ViewMessage } from "@/lib/messages";

export type Conversation = SDKConversation;
export type UserInfo = SDKUserInfo;
export type FriendInfo = SDKFriendInfo;
export type GroupInfo = SDKGroupInfo;
export type GroupMember = SDKGroupMember;
export type GroupApply = SDKGroupApply;
export type FriendApply = SDKFriendApply;
export type Message = ViewMessage;

export const SYSTEM_CONVERSATION_ID = "__system_notifications__";
export const FRIEND_APPLY_UPDATED_EVENT = "im:friend-apply-updated";
export const GROUP_APPLY_UPDATED_EVENT = "im:group-apply-updated";

export interface State {
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
  groupMembersCachedAt: Record<string, number>;
  groupInfoCachedAt: Record<string, number>;
  userProfileCachedAt: Record<string, number>;
}

export type Action =
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

export interface StoreContextType {
  state: State;
  dispatch: Dispatch<Action>;
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
  fetchGroupMembers: (groupId: string, options?: { force?: boolean }) => Promise<void>;
  fetchGroupInfo: (groupId: string, options?: { force?: boolean }) => Promise<void>;
  fetchUserProfile: (userId: string, options?: { force?: boolean }) => Promise<void>;
  markConversationRead: (conversationId: string, seq?: number) => Promise<void>;
  refreshSystemMessages: () => Promise<void>;
  openSingleChat: (input: OpenSingleChatInput) => void;
  openGroupChat: (input: OpenGroupChatInput) => void;
  removeConversationLocal: (conversationId: string) => void;
  refreshAfterMembershipChanged: () => Promise<void>;
}

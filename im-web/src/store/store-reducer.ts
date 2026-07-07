import { ConversationType } from "im-sdk";
import { AUTH_REFRESH_TOKEN_KEY, AUTH_TOKEN_KEY, getStoredAuthUserId } from "@/config/storage-keys";
import {
  createGroupConversation,
  createSingleConversation,
  groupsFromConversations,
  groupConversationId,
  mergeProfileCaches,
  profilesFromConversations,
  profilesFromFriends,
  profilesFromGroupMembers,
} from "@/store/store-helpers";
import {
  mergeConversationMessages,
  normalizeConversation,
  setConversationsKeepingActive,
  sortConversations,
  upsertConversation,
} from "@/store/domain";
import { toRevokedMessage } from "@/lib/messages";
import type { Action, State } from "@/store/store-types";

export const initialState: State = {
  token: localStorage.getItem(AUTH_TOKEN_KEY),
  refreshToken: localStorage.getItem(AUTH_REFRESH_TOKEN_KEY),
  userId: getStoredAuthUserId(),
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
  groupMembersCachedAt: {},
  groupInfoCachedAt: {},
  userProfileCachedAt: {},
};

export function reducer(state: State, action: Action): State {
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
      return mergeProfileCaches(
        setConversationsKeepingActive(state, action.list) as State,
        profilesFromConversations(action.list),
        groupsFromConversations(action.list),
      );
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
          [action.conversationId]: existing.map((message) =>
            message.seq === action.seq ? toRevokedMessage(message) : message
          ),
        },
      };
    }
    case "SET_FRIENDS":
      return mergeProfileCaches({ ...state, friends: action.list }, profilesFromFriends(action.list), []);
    case "SET_MY_GROUPS":
      return mergeProfileCaches({ ...state, myGroups: action.list }, [], action.list);
    case "SET_SEARCH_USERS":
      return mergeProfileCaches({ ...state, searchUsers: action.list }, action.list, []);
    case "SET_SEARCH_GROUPS":
      return mergeProfileCaches({ ...state, searchGroups: action.list }, [], action.list);
    case "SET_UNHANDLED_APPLY_COUNT":
      return { ...state, unhandledApplyCount: action.count };
    case "SET_UNHANDLED_GROUP_APPLY_COUNT":
      return { ...state, unhandledGroupApplyCount: action.count };
    case "SET_ACTIVE_CONVERSATION": {
      const cleared = action.conversationId
        ? state.conversations.map((conversation) =>
            conversation.conversationId === action.conversationId ? { ...conversation, unreadCount: 0 } : conversation
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
    case "ADD_FRIEND":
      if (state.friends.some((friend) => friend.friendUserId === action.friend.friendUserId)) return state;
      return { ...state, friends: [...state.friends, action.friend] };
    case "REMOVE_FRIEND":
      return { ...state, friends: state.friends.filter((friend) => friend.friendUserId !== action.friendUserId) };
    case "ADD_CONVERSATION":
      return { ...state, conversations: upsertConversation(state.conversations, { conversation: action.conversation }) };
    case "REMOVE_CONVERSATION": {
      const messages = { ...state.messages };
      delete messages[action.conversationId];
      return {
        ...state,
        conversations: state.conversations.filter((conversation) => conversation.conversationId !== action.conversationId),
        messages,
        activeConversationId: state.activeConversationId === action.conversationId ? null : state.activeConversationId,
      };
    }
    case "OPEN_SINGLE_CHAT": {
      const existing = state.conversations.find(
        (conversation) => conversation.conversationType === ConversationType.SINGLE && conversation.userId === action.input.userId,
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
        (conversation) => conversation.conversationType === ConversationType.GROUP
          && (conversation.groupId === action.input.groupId || conversation.conversationId === groupConversationId(action.input.groupId)),
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
        conversations: state.conversations.map((conversation) =>
          conversation.conversationId === action.conversationId ? { ...conversation, unreadCount: 0 } : conversation
        ),
      };
    case "UPSERT_SENT_MESSAGE": {
      const nextId = action.conversation.conversationId;
      const previousId = action.previousConversationId;
      const carriedMessages = previousId && previousId !== nextId
        ? (state.messages[previousId] || []).map((message) => ({ ...message, conversationId: nextId }))
        : [];
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
      const convExists = state.conversations.some((conversation) => conversation.conversationId === action.conversationId);
      if (!convExists) return state;
      const isActive = state.activeConversationId === action.conversationId;
      return {
        ...state,
        conversations: sortConversations(state.conversations.map((conversation) =>
          conversation.conversationId === action.conversationId
            ? {
                ...conversation,
                latestMsg: action.latestMsg,
                latestMsgSendTime: action.latestMsgSendTime,
                unreadCount: action.incoming && !isActive ? conversation.unreadCount + 1 : conversation.unreadCount,
              }
            : conversation
        )),
      };
    }
    case "UPDATE_CONVERSATION_UNREAD":
      return {
        ...state,
        conversations: state.conversations.map((conversation) =>
          conversation.conversationId === action.conversationId
            ? { ...conversation, unreadCount: action.unreadCount }
            : conversation
        ),
      };
    case "SET_GROUP_MEMBERS":
      return mergeProfileCaches({
        ...state,
        groupMembers: { ...state.groupMembers, [action.groupId]: action.members },
        groupMembersCachedAt: { ...state.groupMembersCachedAt, [action.groupId]: Date.now() },
      }, profilesFromGroupMembers(action.members), []);
    case "SET_GROUP_INFO":
      return mergeProfileCaches({
        ...state,
        groupInfoCache: { ...state.groupInfoCache, [action.groupId]: action.info },
        groupInfoCachedAt: { ...state.groupInfoCachedAt, [action.groupId]: Date.now() },
      }, [], [action.info]);
    case "SET_USER_PROFILE":
      return mergeProfileCaches(state, [action.info], []);
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

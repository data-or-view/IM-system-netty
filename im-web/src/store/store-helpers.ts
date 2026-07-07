import { ConversationType, MessageReceiveOption, type TokenPair } from "im-sdk";
import { im } from "@/sdk/im-sdk";
import {
  AUTH_REFRESH_TOKEN_KEY,
  AUTH_TOKEN_KEY,
  AUTH_USER_ID_KEY,
  clearStoredSyncCursors,
  getStoredAuthUserId,
  syncCursorsKey,
} from "@/config/storage-keys";
import { normalizeConversation } from "@/store/domain";
import type {
  Conversation,
  FriendInfo,
  GroupInfo,
  GroupMember,
  Message,
  OpenGroupChatInput,
  OpenSingleChatInput,
  State,
  UserInfo,
} from "@/store/store-types";

export function persistTokens(tokens: TokenPair) {
  if (tokens.token) localStorage.setItem(AUTH_TOKEN_KEY, tokens.token);
  if (tokens.refreshToken) localStorage.setItem(AUTH_REFRESH_TOKEN_KEY, tokens.refreshToken);
}

export function currentStoredUserId(stateUserId?: string | null): string | null {
  return getStoredAuthUserId() || stateUserId || null;
}

export function clearStoredAuth(userId?: string | null) {
  clearStoredSyncCursors(userId ?? getStoredAuthUserId());
  localStorage.removeItem(AUTH_USER_ID_KEY);
  localStorage.removeItem(AUTH_TOKEN_KEY);
  localStorage.removeItem(AUTH_REFRESH_TOKEN_KEY);
  im.clearTokens();
}

export function persistSyncCursors(userId: string | null | undefined, messages: Record<string, Message[]>): void {
  if (!userId) return;
  const cursors = Object.entries(messages)
    .map(([conversationId, list]) => ({
      conversationId,
      lastSeq: list.reduce((max, msg) => Math.max(max, msg.seq || 0), 0),
    }))
    .filter((cursor) => cursor.lastSeq > 0);
  sessionStorage.setItem(syncCursorsKey(userId), JSON.stringify(cursors));
}

export function cacheFresh(cachedAt: number | undefined, ttlMs: number): boolean {
  return cachedAt !== undefined && Date.now() - cachedAt < ttlMs;
}

export function mergeProfileCaches(state: State, users: UserInfo[], groups: GroupInfo[]): State {
  const now = Date.now();
  let userProfileCache = state.userProfileCache;
  let userProfileCachedAt = state.userProfileCachedAt;
  let groupInfoCache = state.groupInfoCache;
  let groupInfoCachedAt = state.groupInfoCachedAt;

  for (const user of users) {
    if (!user.userId) continue;
    if (userProfileCache === state.userProfileCache) userProfileCache = { ...state.userProfileCache };
    if (userProfileCachedAt === state.userProfileCachedAt) userProfileCachedAt = { ...state.userProfileCachedAt };
    userProfileCache[user.userId] = mergeUserProfile(userProfileCache[user.userId], user);
    userProfileCachedAt[user.userId] = now;
  }

  for (const group of groups) {
    if (!group.groupId) continue;
    if (groupInfoCache === state.groupInfoCache) groupInfoCache = { ...state.groupInfoCache };
    if (groupInfoCachedAt === state.groupInfoCachedAt) groupInfoCachedAt = { ...state.groupInfoCachedAt };
    groupInfoCache[group.groupId] = mergeGroupInfo(groupInfoCache[group.groupId], group);
    groupInfoCachedAt[group.groupId] = now;
  }

  if (
    userProfileCache === state.userProfileCache &&
    userProfileCachedAt === state.userProfileCachedAt &&
    groupInfoCache === state.groupInfoCache &&
    groupInfoCachedAt === state.groupInfoCachedAt
  ) {
    return state;
  }
  return { ...state, userProfileCache, userProfileCachedAt, groupInfoCache, groupInfoCachedAt };
}

export function profilesFromFriends(friends: FriendInfo[]): UserInfo[] {
  return friends.map((friend) => ({
    userId: friend.friendUserId,
    nickname: friend.remark || friend.nickname,
    faceUrl: friend.faceUrl,
  }));
}

export function profilesFromGroupMembers(members: GroupMember[]): UserInfo[] {
  return members.map((member) => ({
    userId: member.userId,
    nickname: member.nickname,
    faceUrl: member.faceUrl,
  }));
}

export function profilesFromConversations(conversations: Conversation[]): UserInfo[] {
  return conversations
    .filter((conversation) => conversation.conversationType === ConversationType.SINGLE && conversation.userId)
    .map((conversation) => ({
      userId: conversation.userId || "",
      nickname: conversation.showName || conversation.userId,
      faceUrl: conversation.faceUrl,
    }));
}

export function groupsFromConversations(conversations: Conversation[]): GroupInfo[] {
  return conversations
    .filter((conversation) => conversation.conversationType === ConversationType.GROUP && (conversation.groupId || conversation.conversationId))
    .map((conversation) => ({
      groupId: conversation.groupId || conversation.conversationId.replace(/^group_/, ""),
      groupName: conversation.groupName || conversation.showName || conversation.groupId || conversation.conversationId,
      faceUrl: conversation.faceUrl,
    }));
}

export function groupInfoFromConversation(list: Conversation[]): GroupInfo[] {
  return list
    .filter((conversation) => conversation.conversationType === ConversationType.GROUP)
    .map((conversation) => ({
      groupId: conversation.groupId || conversation.conversationId.replace(/^group_/, ""),
      groupName: conversation.groupName || conversation.showName,
      faceUrl: conversation.faceUrl,
    }));
}

export function groupConversationId(groupId: string): string {
  return `group_${groupId}`;
}

export function createSingleConversation(currentUserId: string, input: OpenSingleChatInput): Conversation {
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

export function createGroupConversation(currentUserId: string, input: OpenGroupChatInput): Conversation {
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

function mergeUserProfile(existing: UserInfo | undefined, next: UserInfo): UserInfo {
  return {
    ...existing,
    ...next,
    nickname: next.nickname || existing?.nickname,
    faceUrl: next.faceUrl || existing?.faceUrl,
  };
}

function mergeGroupInfo(existing: GroupInfo | undefined, next: GroupInfo): GroupInfo {
  return {
    ...existing,
    ...next,
    groupName: next.groupName || existing?.groupName || next.groupId,
    faceUrl: next.faceUrl || existing?.faceUrl,
  };
}

function singleConversationId(currentUserId: string, friendUserId: string): string {
  return `single_${[currentUserId, friendUserId].sort().join("_")}`;
}

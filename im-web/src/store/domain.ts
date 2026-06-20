import {
  ApplyHandleResult,
  ConversationType,
  MessageReceiveOption,
  type Conversation as SDKConversation,
  type FriendApply as SDKFriendApply,
  type GroupApply as SDKGroupApply,
  type Message as SDKMessage,
  type MessageRevoked,
  type SystemMessageSummary,
} from "im-sdk";
import { toViewMessage, type ViewMessage } from "@/lib/messages";

export type Conversation = SDKConversation;
export type Message = ViewMessage;

export const MAX_MESSAGES_PER_CONVERSATION = 500;

export type ClientDomainEvent =
  | { type: "MESSAGE_RECEIVED"; messages: SDKMessage[]; currentUserId: string | null; activeConversationId: string | null }
  | { type: "MESSAGE_REVOKED"; event: MessageRevoked }
  | { type: "FRIEND_APPLY_UPDATED"; apply: SDKFriendApply }
  | { type: "GROUP_APPLY_UPDATED"; apply: SDKGroupApply }
  | { type: "SYSTEM_MESSAGE_RECEIVED"; message: SystemMessageSummary }
  | { type: "RECONNECTED_SYNCED" };

export interface ConversationUpsertInput {
  conversation: Conversation;
}

export interface MessageMergeInput {
  conversationId: string;
  messages: Message[];
}

export type PushRefreshTask =
  | "conversations"
  | "friends"
  | "myGroups"
  | "friendApplyCount"
  | "groupApplyCount"
  | "systemUnreadCount";

export interface DomainStateShape {
  userId: string | null;
  activeConversationId: string | null;
  conversations: Conversation[];
  messages: Record<string, Message[]>;
  systemMessages: Array<SystemMessageSummary & { userId: string; content?: string; readAt?: number }>;
  systemUnreadCount: number;
  latestSystemMessage: SystemMessageSummary | null;
}

export interface DomainEventResult {
  state: DomainStateShape;
  refreshTasks: PushRefreshTask[];
}

export function normalizeConversation(conversation: Conversation): Conversation {
  const showName = compactText(conversation.showName)
    ?? compactText(conversation.groupName)
    ?? compactText(conversation.userId)
    ?? compactText(conversation.groupId)
    ?? (conversation.conversationType === ConversationType.GROUP ? "未命名群聊" : "未知用户");
  const groupName = conversation.conversationType === ConversationType.GROUP
    ? compactText(conversation.groupName) ?? showName
    : conversation.groupName;
  return { ...conversation, showName, groupName };
}

export function sortConversations(list: Conversation[]): Conversation[] {
  return [...list].sort((a, b) => {
    if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1;
    return (b.latestMsgSendTime || 0) - (a.latestMsgSendTime || 0);
  });
}

export function mergeConversationMessages(existing: Message[], incoming: Message[]): Message[] {
  if (incoming.length === 0) return existing;

  const byKey = new Map<string, Message>();
  const aliasKey = new Map<string, string>();
  for (const msg of existing) {
    const key = messageKey(msg);
    byKey.set(key, msg);
    for (const alias of messageAliases(msg)) {
      aliasKey.set(alias, key);
    }
  }

  let changed = false;
  for (const msg of incoming) {
    const key = aliasKey.get(messageKey(msg)) ?? messageKey(msg);
    const current = byKey.get(key);
    if (!current) {
      byKey.set(key, msg);
      for (const alias of messageAliases(msg)) {
        aliasKey.set(alias, key);
      }
      changed = true;
      continue;
    }
    const merged = { ...current, ...msg };
    if (!sameMessage(current, merged)) {
      byKey.set(key, merged);
      for (const alias of messageAliases(merged)) {
        aliasKey.set(alias, key);
      }
      changed = true;
    }
  }

  if (!changed) return existing;

  const merged = Array.from(byKey.values()).sort(
    (a, b) => a.seq - b.seq || a.createTime - b.createTime,
  );

  if (merged.length <= MAX_MESSAGES_PER_CONVERSATION) {
    return merged;
  }
  return merged.slice(merged.length - MAX_MESSAGES_PER_CONVERSATION);
}

export function upsertConversation(list: Conversation[], input: ConversationUpsertInput): Conversation[] {
  const conversation = normalizeConversation(input.conversation);
  let found = false;
  const next = list.map((item) => {
    if (item.conversationId !== conversation.conversationId) return item;
    found = true;
    return normalizeConversation({ ...item, ...conversation });
  });
  if (!found) {
    next.push(conversation);
  }
  return sortConversations(next);
}

export function setConversationsKeepingActive(
  state: DomainStateShape,
  list: Conversation[],
): DomainStateShape {
  const normalized = sortConversations(list.map(normalizeConversation));
  return {
    ...state,
    conversations: normalized,
  };
}

export function applyDomainEvent(state: DomainStateShape, event: ClientDomainEvent): DomainEventResult {
  switch (event.type) {
    case "MESSAGE_RECEIVED":
      return applyMessageReceived(state, event);
    case "MESSAGE_REVOKED":
      return applyMessageRevoked(state, event.event);
    case "FRIEND_APPLY_UPDATED":
      return applyFriendApplyUpdated(state, event.apply);
    case "GROUP_APPLY_UPDATED":
      return applyGroupApplyUpdated(state, event.apply);
    case "SYSTEM_MESSAGE_RECEIVED":
      return applySystemMessageReceived(state, event.message);
    case "RECONNECTED_SYNCED":
      return { state, refreshTasks: ["conversations", "friendApplyCount", "groupApplyCount", "systemUnreadCount"] };
  }
}

export function createConversationFromMessage(msg: Message, currentUserId: string | null): Conversation {
  const isGroup = msg.conversationId.startsWith("group_");
  return normalizeConversation({
    conversationId: msg.conversationId,
    ownerUserId: currentUserId || "",
    conversationType: isGroup ? ConversationType.GROUP : ConversationType.SINGLE,
    userId: isGroup ? undefined : otherUserIdFromMessage(msg, currentUserId),
    groupId: isGroup ? msg.conversationId.replace(/^group_/, "") : undefined,
    groupName: isGroup ? "群聊" : undefined,
    showName: isGroup ? "群聊" : otherUserIdFromMessage(msg, currentUserId) ?? "未知用户",
    latestMsg: msg.content,
    latestMsgSendTime: msg.createTime,
    unreadCount: 0,
    recvMsgOpt: MessageReceiveOption.NORMAL,
    isPinned: false,
  });
}

export function latestMessage(messages: Message[]): Message | null {
  if (messages.length === 0) return null;
  return messages.reduce((prev, current) => (
    current.createTime >= prev.createTime ? current : prev
  ));
}

export function messageKey(msg: Message): string {
  if (msg.messageId) return `id:${msg.messageId}`;
  if (msg.seq > 0) return `seq:${msg.conversationId}:${msg.seq}`;
  return `tmp:${msg.senderUserId}:${msg.createTime}:${msg.content}`;
}

function messageAliases(msg: Message): string[] {
  const aliases = [messageKey(msg)];
  if (msg.messageId) aliases.push(`id:${msg.messageId}`);
  if (msg.seq > 0) aliases.push(`seq:${msg.conversationId}:${msg.seq}`);
  return Array.from(new Set(aliases));
}

export function compactText(value?: string | null): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed || trimmed === "undefined" || trimmed === "null") return undefined;
  return trimmed;
}

function applyMessageReceived(
  state: DomainStateShape,
  event: Extract<ClientDomainEvent, { type: "MESSAGE_RECEIVED" }>,
): DomainEventResult {
  let nextState = state;
  const refreshTasks = new Set<PushRefreshTask>();
  const grouped = new Map<string, Message[]>();
  for (const sdkMsg of event.messages) {
    const msg = toViewMessage(sdkMsg);
    if (!msg.conversationId) continue;
    const list = grouped.get(msg.conversationId) || [];
    list.push(msg);
    grouped.set(msg.conversationId, list);
  }

  for (const [conversationId, messages] of grouped) {
    const existing = nextState.messages[conversationId] || [];
    const merged = mergeConversationMessages(existing, messages);
    const latest = latestMessage(messages);
    if (!latest) continue;

    const conversationExists = nextState.conversations.some((item) => item.conversationId === conversationId);
    const active = event.activeConversationId === conversationId;
    const unreadDelta = active
      ? 0
      : messages.filter((message) => message.senderUserId !== event.currentUserId).length;
    const baseConversation = conversationExists
      ? nextState.conversations.find((item) => item.conversationId === conversationId)!
      : createConversationFromMessage(latest, event.currentUserId);

    nextState = {
      ...nextState,
      messages: {
        ...nextState.messages,
        [conversationId]: merged,
      },
      conversations: upsertConversation(nextState.conversations, {
        conversation: {
          ...baseConversation,
          latestMsg: latest.content,
          latestMsgSendTime: latest.createTime,
          unreadCount: active ? 0 : baseConversation.unreadCount + unreadDelta,
        },
      }),
    };

    if (!conversationExists) {
      refreshTasks.add("conversations");
    }
  }

  return { state: nextState, refreshTasks: Array.from(refreshTasks) };
}

function applyMessageRevoked(state: DomainStateShape, event: MessageRevoked): DomainEventResult {
  const existing = state.messages[event.conversationId] || [];
  const target = existing.find((message) => message.seq === event.seq);
  const messages = existing.map((message) =>
    message.seq === event.seq ? { ...message, contentType: 101, content: "消息已撤回" } : message
  );
  const currentLatest = latestMessage(existing);
  const shouldUpdateLatest = !currentLatest || !target || currentLatest.seq === event.seq;
  const conversations = state.conversations.map((conversation) =>
    conversation.conversationId === event.conversationId && shouldUpdateLatest
      ? { ...conversation, latestMsg: "消息已撤回", latestMsgSendTime: Date.now() }
      : conversation
  );
  return {
    state: {
      ...state,
      messages: { ...state.messages, [event.conversationId]: messages },
      conversations: sortConversations(conversations),
    },
    refreshTasks: [],
  };
}

function applyFriendApplyUpdated(state: DomainStateShape, apply: SDKFriendApply): DomainEventResult {
  const refreshTasks: PushRefreshTask[] = ["friendApplyCount"];
  if (apply.handleResult === ApplyHandleResult.AGREED || apply.handleResult === ApplyHandleResult.REJECTED) {
    refreshTasks.push("friends", "conversations");
  }
  return { state, refreshTasks };
}

function applyGroupApplyUpdated(state: DomainStateShape, apply: SDKGroupApply): DomainEventResult {
  const refreshTasks: PushRefreshTask[] = ["groupApplyCount"];
  if (apply.handleResult === ApplyHandleResult.AGREED || apply.handleResult === ApplyHandleResult.REJECTED) {
    refreshTasks.push("myGroups", "conversations");
  }
  return { state, refreshTasks };
}

function applySystemMessageReceived(state: DomainStateShape, message: SystemMessageSummary): DomainEventResult {
  const refreshTasks = new Set<PushRefreshTask>(["systemUnreadCount"]);
  if (isGroupMembershipSystemMessage(message)) {
    refreshTasks.add("myGroups");
    refreshTasks.add("conversations");
  }
  return {
    state: {
      ...state,
      latestSystemMessage: message,
      systemUnreadCount: state.systemUnreadCount + 1,
      systemMessages: [
        { ...message, userId: state.userId || "", content: "", readAt: 0 },
        ...state.systemMessages.filter((item) => item.messageId !== message.messageId),
      ],
    },
    refreshTasks: Array.from(refreshTasks),
  };
}

function isGroupMembershipSystemMessage(message: SystemMessageSummary): boolean {
  const channelId = message.channelId?.toLowerCase();
  const text = `${message.title || ""} ${message.summary || ""}`.toLowerCase();
  return channelId === "group" && (
    text.includes("group_invited")
    || text.includes("group_created")
    || text.includes("group_member_joined")
    || message.title === "你已加入群聊"
  );
}

function sameMessage(left: Message, right: Message): boolean {
  return left.messageId === right.messageId
    && left.seq === right.seq
    && left.senderUserId === right.senderUserId
    && left.conversationId === right.conversationId
    && left.contentType === right.contentType
    && left.content === right.content
    && left.createTime === right.createTime
    && left.status === right.status;
}

function otherUserIdFromMessage(msg: Message, currentUserId: string | null): string | undefined {
  if (msg.senderUserId && msg.senderUserId !== currentUserId) {
    return msg.senderUserId;
  }
  return undefined;
}

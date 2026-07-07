import { useCallback, useEffect, useState } from "react";
import {
  ConversationType,
  GroupMemberRole,
  MessageContentType,
  groupMemberRoleRank,
  type GroupCallSession,
} from "im-sdk";
import { useCall } from "@/components/call/CallProvider";
import { im } from "@/sdk/im-sdk";
import type { Conversation, GroupMember, Message } from "@/store/store";

interface UseActiveGroupCallOptions {
  conversation: Conversation | undefined;
  messages: Message[];
  groupMembers: GroupMember[];
  currentUserId: string | null;
  fetchGroupMembers: (groupId: string, options?: { force?: boolean }) => Promise<void>;
}

export function useActiveGroupCall({
  conversation,
  messages,
  groupMembers,
  currentUserId,
  fetchGroupMembers,
}: UseActiveGroupCallOptions) {
  const [groupCallBusy, setGroupCallBusy] = useState(false);
  const [activeGroupCall, setActiveGroupCall] = useState<GroupCallSession | null>(null);
  const { startCall, startGroupCall, joinGroupCall, endGroupCall } = useCall();

  const currentGroupMember = groupMembers.find((member) => member.userId === currentUserId);
  const canEndActiveGroupCall = Boolean(
    activeGroupCall &&
      (activeGroupCall.initiatorUserId === currentUserId ||
        groupMemberRoleRank(currentGroupMember?.roleLevel) >=
          groupMemberRoleRank(GroupMemberRole.ADMIN))
  );

  useEffect(() => {
    let cancelled = false;
    if (conversation?.conversationType !== ConversationType.GROUP || !conversation.groupId) {
      setActiveGroupCall(null);
      return;
    }

    const loadActiveGroupCall = async () => {
      try {
        await fetchGroupMembers(conversation.groupId!);
        const active = await im.group.activeCall(conversation.groupId!);
        if (!cancelled) setActiveGroupCall(active.active ? active : null);
      } catch {
        if (!cancelled) setActiveGroupCall(null);
      }
    };

    void loadActiveGroupCall();
    return () => {
      cancelled = true;
    };
  }, [conversation?.conversationType, conversation?.groupId, fetchGroupMembers]);

  const refreshActiveGroupCall = useCallback(async (groupId: string) => {
    try {
      const active = await im.group.activeCall(groupId);
      setActiveGroupCall(active.active ? active : null);
    } catch {
      setActiveGroupCall(null);
    }
  }, []);

  useEffect(() => {
    if (conversation?.conversationType !== ConversationType.GROUP || !conversation.groupId) return;
    const hasGroupSignal = messages.some((msg) => msg.contentType === MessageContentType.SIGNAL);
    if (!hasGroupSignal) return;

    let cancelled = false;
    const refresh = async () => {
      if (!cancelled) await refreshActiveGroupCall(conversation.groupId!);
    };
    void refresh();

    return () => {
      cancelled = true;
    };
  }, [conversation?.conversationType, conversation?.groupId, messages, refreshActiveGroupCall]);

  const handleStartCall = useCallback(
    (callType: "voice" | "video") => {
      if (!conversation?.userId || conversation.conversationType === ConversationType.GROUP) return;
      void startCall({
        callType,
        peer: { userId: conversation.userId, name: conversation.showName, faceUrl: conversation.faceUrl },
      });
    },
    [conversation, startCall]
  );

  const handleStartGroupCall = useCallback(() => {
    if (!conversation?.groupId || conversation.conversationType !== ConversationType.GROUP || groupCallBusy) return;
    setGroupCallBusy(true);
    void startGroupCall({
      callType: "video",
      group: {
        groupId: conversation.groupId,
        name: conversation.showName,
        faceUrl: conversation.faceUrl,
        canEnd: true,
      },
    }).finally(async () => {
      await refreshActiveGroupCall(conversation.groupId!);
      setGroupCallBusy(false);
    });
  }, [conversation, groupCallBusy, refreshActiveGroupCall, startGroupCall]);

  const handleJoinGroupCall = useCallback(() => {
    if (!conversation?.groupId || conversation.conversationType !== ConversationType.GROUP || groupCallBusy) return;
    setGroupCallBusy(true);
    void joinGroupCall({
      group: {
        groupId: conversation.groupId,
        name: conversation.showName,
        faceUrl: conversation.faceUrl,
        canEnd: canEndActiveGroupCall,
      },
    }).finally(async () => {
      await refreshActiveGroupCall(conversation.groupId!);
      setGroupCallBusy(false);
    });
  }, [canEndActiveGroupCall, conversation, groupCallBusy, joinGroupCall, refreshActiveGroupCall]);

  const handleEndGroupCall = useCallback(() => {
    if (!conversation?.groupId || conversation.conversationType !== ConversationType.GROUP || groupCallBusy) return;
    setGroupCallBusy(true);
    void endGroupCall().finally(async () => {
      await refreshActiveGroupCall(conversation.groupId!);
      setGroupCallBusy(false);
    });
  }, [conversation, endGroupCall, groupCallBusy, refreshActiveGroupCall]);

  return {
    activeGroupCall,
    groupCallBusy,
    canEndActiveGroupCall,
    handleStartCall,
    handleStartGroupCall,
    handleJoinGroupCall,
    handleEndGroupCall,
  };
}

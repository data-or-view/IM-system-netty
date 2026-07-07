import { useCallback, useState } from "react";
import {
  GroupMemberRole,
  getErrorText,
  type GroupMemberRoleValue,
} from "im-sdk";
import { toast } from "sonner";
import { emptyConfirmDialog, type ConfirmDialogState } from "@/components/ConfirmDialog";
import { im } from "@/sdk/im-sdk";
import type { StoreContextType } from "@/store/store-types";

interface UseGroupManagementOptions {
  groupId?: string;
  fetchGroupInfo: StoreContextType["fetchGroupInfo"];
  fetchGroupMembers: StoreContextType["fetchGroupMembers"];
  fetchConversations: StoreContextType["fetchConversations"];
  quitGroup: StoreContextType["quitGroup"];
  removeConversationLocal: StoreContextType["removeConversationLocal"];
  refreshAfterMembershipChanged: StoreContextType["refreshAfterMembershipChanged"];
  navigateToChat: () => void;
}

export function useGroupManagement({
  groupId,
  fetchGroupInfo,
  fetchGroupMembers,
  fetchConversations,
  quitGroup,
  removeConversationLocal,
  refreshAfterMembershipChanged,
  navigateToChat,
}: UseGroupManagementOptions) {
  const [kicking, setKicking] = useState<Record<string, boolean>>({});
  const [roleChanging, setRoleChanging] = useState<Record<string, boolean>>({});
  const [transferring, setTransferring] = useState<Record<string, boolean>>({});
  const [confirm, setConfirm] = useState<ConfirmDialogState>(emptyConfirmDialog);

  const openConfirm = useCallback((next: Omit<ConfirmDialogState, "open">) => {
    setConfirm({ ...next, open: true });
  }, []);

  const refreshGroupManagementState = useCallback(async () => {
    if (!groupId) return;
    await Promise.all([
      fetchGroupInfo(groupId, { force: true }),
      fetchGroupMembers(groupId, { force: true }),
      fetchConversations(),
    ]);
  }, [fetchConversations, fetchGroupInfo, fetchGroupMembers, groupId]);

  const handleKick = useCallback(async (userId: string) => {
    if (!groupId) return;
    setConfirm((prev) => ({ ...prev, loading: true }));
    setKicking((prev) => ({ ...prev, [userId]: true }));
    try {
      await im.group.kick(groupId, userId);
      await refreshGroupManagementState();
      toast("已踢出");
      setConfirm(emptyConfirmDialog);
    } catch (err) {
      toast(`踢出失败：${getErrorText(err)}`);
      setConfirm((prev) => ({ ...prev, loading: false }));
    } finally {
      setKicking((prev) => ({ ...prev, [userId]: false }));
    }
  }, [groupId, refreshGroupManagementState]);

  const handleSetRole = useCallback(async (memberId: string, roleLevel: GroupMemberRoleValue) => {
    if (!groupId) return;
    setConfirm((prev) => ({ ...prev, loading: true }));
    setRoleChanging((prev) => ({ ...prev, [memberId]: true }));
    try {
      await im.group.setMemberRole(groupId, memberId, roleLevel);
      await refreshGroupManagementState();
      toast(roleLevel === GroupMemberRole.ADMIN ? "已设为管理员" : "已取消管理员");
      setConfirm(emptyConfirmDialog);
    } catch (err) {
      toast(`设置角色失败：${getErrorText(err)}`);
      setConfirm((prev) => ({ ...prev, loading: false }));
    } finally {
      setRoleChanging((prev) => ({ ...prev, [memberId]: false }));
    }
  }, [groupId, refreshGroupManagementState]);

  const handleTransferOwner = useCallback(async (memberId: string) => {
    if (!groupId) return;
    setConfirm((prev) => ({ ...prev, loading: true }));
    setTransferring((prev) => ({ ...prev, [memberId]: true }));
    try {
      await im.group.transferOwner(groupId, memberId);
      await refreshGroupManagementState();
      toast("群主已转让");
      setConfirm(emptyConfirmDialog);
    } catch (err) {
      toast(`转让失败：${getErrorText(err)}`);
      setConfirm((prev) => ({ ...prev, loading: false }));
    } finally {
      setTransferring((prev) => ({ ...prev, [memberId]: false }));
    }
  }, [groupId, refreshGroupManagementState]);

  const handleDisband = useCallback(async () => {
    if (!groupId) return;
    setConfirm((prev) => ({ ...prev, loading: true }));
    try {
      await im.group.disband(groupId);
      removeConversationLocal(`group_${groupId}`);
      await refreshAfterMembershipChanged();
      toast("群已解散");
      setConfirm(emptyConfirmDialog);
      navigateToChat();
    } catch (err) {
      toast(`解散失败：${getErrorText(err)}`);
      setConfirm((prev) => ({ ...prev, loading: false }));
    }
  }, [groupId, navigateToChat, refreshAfterMembershipChanged, removeConversationLocal]);

  const handleQuit = useCallback(async () => {
    if (!groupId) return;
    setConfirm((prev) => ({ ...prev, loading: true }));
    try {
      await quitGroup(groupId);
      toast("已退出群");
      setConfirm(emptyConfirmDialog);
      navigateToChat();
    } catch (err) {
      toast(`退出失败：${getErrorText(err)}`);
      setConfirm((prev) => ({ ...prev, loading: false }));
    }
  }, [groupId, navigateToChat, quitGroup]);

  const confirmKick = useCallback((memberId: string) => {
    openConfirm({
      title: "移出群成员？",
      description: "该成员会被移出当前群聊，并从会话列表中移除这个群。",
      confirmText: "移出",
      tone: "danger",
      onConfirm: () => handleKick(memberId),
    });
  }, [handleKick, openConfirm]);

  const confirmSetRole = useCallback((memberId: string, roleLevel: GroupMemberRoleValue) => {
    const toAdmin = roleLevel === GroupMemberRole.ADMIN;
    openConfirm({
      title: toAdmin ? "设为管理员？" : "取消管理员？",
      description: toAdmin
        ? "管理员可以管理群资料、审批申请和处理部分成员操作。"
        : "取消后该成员将恢复为普通成员。",
      confirmText: toAdmin ? "设为管理员" : "取消管理员",
      tone: toAdmin ? "warning" : "default",
      onConfirm: () => handleSetRole(memberId, roleLevel),
    });
  }, [handleSetRole, openConfirm]);

  const confirmTransferOwner = useCallback((memberId: string) => {
    openConfirm({
      title: "转让群主？",
      description: "转让后你会变为普通成员，新群主将拥有群管理权限。",
      confirmText: "转让群主",
      tone: "warning",
      onConfirm: () => handleTransferOwner(memberId),
    });
  }, [handleTransferOwner, openConfirm]);

  const confirmDisband = useCallback(() => {
    openConfirm({
      title: "解散这个群？",
      description: "解散后所有成员都会失去这个群聊，会话也会被移除。这个操作不可撤销。",
      confirmText: "解散群",
      tone: "danger",
      onConfirm: handleDisband,
    });
  }, [handleDisband, openConfirm]);

  const confirmQuit = useCallback(() => {
    openConfirm({
      title: "退出这个群？",
      description: "退出后你将不再接收这个群的消息，群会话会从你的列表中移除。",
      confirmText: "退出群",
      tone: "danger",
      onConfirm: handleQuit,
    });
  }, [handleQuit, openConfirm]);

  return {
    confirm,
    setConfirm,
    kicking,
    roleChanging,
    transferring,
    refreshGroupManagementState,
    confirmKick,
    confirmSetRole,
    confirmTransferOwner,
    confirmDisband,
    confirmQuit,
  };
}

import { type FormEvent, useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  GroupJoinVerification,
  GroupMemberRole,
  getErrorText,
  groupMemberRoleRank,
  type GroupMemberRoleValue,
} from "im-sdk";
import { LogOut, Pencil, Trash2, Users } from "lucide-react";
import { toast } from "sonner";
import { AppPage, Surface } from "@/components/AppPage";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { LoadingState, StateBadge } from "@/components/design-system";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { APP_ROUTES } from "@/config/routes";
import { shortId } from "@/lib/display-formatters";
import { GroupEditDialogs, type GroupInfoFormState } from "@/pages/group-info/GroupEditDialogs";
import { GroupMemberList } from "@/pages/group-info/GroupMemberList";
import { joinPolicyText, roleText } from "@/pages/group-info/group-info-utils";
import { useGroupManagement } from "@/pages/group-info/useGroupManagement";
import { im } from "@/sdk/im-sdk";
import { useStore } from "@/store/store";

export default function GroupInfoPage() {
  const { groupId } = useParams<{ groupId: string }>();
  const navigate = useNavigate();
  const {
    state,
    fetchGroupMembers,
    fetchGroupInfo,
    fetchConversations,
    quitGroup,
    removeConversationLocal,
    refreshAfterMembershipChanged,
  } = useStore();
  const [currentUserRole, setCurrentUserRole] = useState<GroupMemberRoleValue>(GroupMemberRole.MEMBER);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [infoEditOpen, setInfoEditOpen] = useState(false);
  const [nicknameEditOpen, setNicknameEditOpen] = useState(false);
  const [savingInfo, setSavingInfo] = useState(false);
  const [savingNickname, setSavingNickname] = useState(false);
  const [infoForm, setInfoForm] = useState<GroupInfoFormState>({
    groupName: "",
    faceUrl: "",
    notification: "",
    introduction: "",
    needVerification: GroupJoinVerification.DIRECT,
  });
  const [nickname, setNickname] = useState("");

  const navigateToChat = useCallback(() => navigate(APP_ROUTES.chat), [navigate]);
  const groupInfo = groupId ? state.groupInfoCache[groupId] : undefined;
  const members = groupId ? state.groupMembers[groupId] || [] : [];
  const currentMember = members.find((member) => member.userId === state.userId);

  const {
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
  } = useGroupManagement({
    groupId,
    fetchGroupInfo,
    fetchGroupMembers,
    fetchConversations,
    quitGroup,
    removeConversationLocal,
    refreshAfterMembershipChanged,
    navigateToChat,
  });

  useEffect(() => {
    if (!groupId) return;
    setLoading(true);
    setLoadError("");
    Promise.all([
      fetchGroupInfo(groupId),
      fetchGroupMembers(groupId),
    ])
      .catch((err) => setLoadError(getErrorText(err)))
      .finally(() => setLoading(false));
  }, [fetchGroupInfo, fetchGroupMembers, groupId]);

  useEffect(() => {
    if (!state.userId) return;
    const me = members.find((member) => member.userId === state.userId);
    if (me) setCurrentUserRole(me.roleLevel);
  }, [members, state.userId]);

  useEffect(() => {
    if (!groupInfo) return;
    setInfoForm({
      groupName: groupInfo.groupName || "",
      faceUrl: groupInfo.faceUrl || "",
      notification: groupInfo.notification || "",
      introduction: groupInfo.introduction || "",
      needVerification: groupInfo.needVerification || GroupJoinVerification.DIRECT,
    });
  }, [groupInfo]);

  useEffect(() => {
    setNickname(currentMember?.nickname || "");
  }, [currentMember?.nickname]);

  const currentUserRoleRank = groupMemberRoleRank(currentUserRole);
  const isOwner = currentUserRole === GroupMemberRole.OWNER;
  const isAdmin = currentUserRoleRank >= groupMemberRoleRank(GroupMemberRole.ADMIN);
  const canEditGroupInfo = isAdmin;

  const handleSaveGroupInfo = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!groupId) return;
    const groupName = infoForm.groupName.trim();
    if (!groupName) {
      toast("群名称不能为空");
      return;
    }
    setSavingInfo(true);
    try {
      await im.group.updateInfo(groupId, {
        groupName,
        faceUrl: infoForm.faceUrl.trim(),
        notification: infoForm.notification.trim(),
        introduction: infoForm.introduction.trim(),
        needVerification: infoForm.needVerification,
      });
      await refreshGroupManagementState();
      setInfoEditOpen(false);
      toast("群资料已更新");
    } catch (err) {
      toast(`更新群资料失败：${getErrorText(err)}`);
    } finally {
      setSavingInfo(false);
    }
  };

  const handleSaveNickname = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!groupId) return;
    const nextNickname = nickname.trim();
    if (!nextNickname) {
      toast("群昵称不能为空");
      return;
    }
    setSavingNickname(true);
    try {
      await im.group.updateMyGroupNickname(groupId, nextNickname);
      await fetchGroupMembers(groupId, { force: true });
      setNicknameEditOpen(false);
      toast("群昵称已更新");
    } catch (err) {
      toast(`更新群昵称失败：${getErrorText(err)}`);
    } finally {
      setSavingNickname(false);
    }
  };

  if (loading) {
    return <LoadingState text="正在读取群资料" />;
  }

  if (loadError) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="text-center">
          <p className="text-sm text-slate-600">加载群信息失败：{loadError}</p>
          <Button variant="outline" className="mt-3" onClick={navigateToChat}>
            返回聊天
          </Button>
        </div>
      </div>
    );
  }

  if (!groupInfo) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-sm text-slate-500">群不存在</p>
      </div>
    );
  }

  return (
    <AppPage title="群信息" description={groupInfo.groupName} onBack={navigateToChat}>
      <ScrollArea className="h-full">
        <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-5 py-5">
          <Surface className="overflow-hidden">
            <div className="border-b border-slate-100 bg-white px-5 py-5">
              <div className="flex items-center gap-4">
                <Avatar className="h-16 w-16 border border-slate-200 shadow-sm">
                  <AvatarImage src={groupInfo.faceUrl} alt={groupInfo.groupName} />
                  <AvatarFallback className="bg-blue-100 text-xl font-semibold text-blue-700">
                    {groupInfo.groupName.charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div className="min-w-0">
                  <h2 className="truncate text-lg font-semibold text-[var(--text-strong)]">
                    {groupInfo.groupName}
                  </h2>
                  <p className="mt-1 truncate text-sm text-[var(--text-muted)]" title={groupInfo.groupId}>
                    ID: {shortId(groupInfo.groupId)}
                  </p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <StateBadge tone="info">
                      <Users className="h-3.5 w-3.5" />
                      {groupInfo.memberCount || members.length} 人
                    </StateBadge>
                    <StateBadge tone={isAdmin ? "warning" : "muted"}>{roleText(currentUserRole)}</StateBadge>
                  </div>
                </div>
                {canEditGroupInfo && (
                  <Button
                    variant="secondary"
                    size="sm"
                    className="ml-auto shrink-0"
                    onClick={() => setInfoEditOpen(true)}
                  >
                    <Pencil className="h-3.5 w-3.5" />
                    编辑
                  </Button>
                )}
              </div>
            </div>
            <div className="grid gap-3 px-5 py-4 sm:grid-cols-2">
              <InfoItem label="群主" value={groupInfo.ownerUserId || "-"} />
              <InfoItem label="我的权限" value={roleText(currentUserRole)} />
              <InfoItem label="入群方式" value={joinPolicyText(groupInfo.needVerification)} />
              <InfoItem label="群类型" value={groupInfo.groupType === "PUBLIC" ? "公开群" : "私有群"} />
            </div>
            {(groupInfo.notification || groupInfo.introduction) && (
              <div className="space-y-3 border-t border-slate-100 px-5 py-4">
                {groupInfo.notification && <InfoText title="群公告" text={groupInfo.notification} />}
                {groupInfo.introduction && <InfoText title="群简介" text={groupInfo.introduction} />}
              </div>
            )}
          </Surface>

          <Surface className="p-4">
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <div className="text-sm font-semibold text-slate-900">我的群昵称</div>
                <div className="mt-1 truncate text-sm text-slate-500">
                  {currentMember?.nickname || "暂未设置"}
                </div>
              </div>
              <Button variant="outline" size="sm" onClick={() => setNicknameEditOpen(true)}>
                <Pencil className="h-3.5 w-3.5" />
                修改
              </Button>
            </div>
          </Surface>

          <GroupMemberList
            members={members}
            currentUserId={state.userId}
            isOwner={isOwner}
            isAdmin={isAdmin}
            kicking={kicking}
            roleChanging={roleChanging}
            transferring={transferring}
            onOpenUser={(userId) => navigate(APP_ROUTES.user(userId))}
            onKick={confirmKick}
            onSetRole={confirmSetRole}
            onTransferOwner={confirmTransferOwner}
          />

          <Surface className="p-4">
            <div className="mb-3 text-sm font-semibold text-red-700">危险操作</div>
            <div className="space-y-2">
              {isOwner && (
                <Button variant="destructive" className="w-full justify-start" onClick={confirmDisband}>
                  <Trash2 className="h-4 w-4" />
                  解散群
                </Button>
              )}
              {!isOwner && (
                <Button
                  variant="outline"
                  className="w-full justify-start text-red-600 hover:border-red-200 hover:bg-red-50 hover:text-red-700"
                  onClick={confirmQuit}
                >
                  <LogOut className="h-4 w-4" />
                  退出群
                </Button>
              )}
            </div>
          </Surface>
        </div>
      </ScrollArea>
      <GroupEditDialogs
        infoOpen={infoEditOpen}
        onInfoOpenChange={setInfoEditOpen}
        infoForm={infoForm}
        setInfoForm={setInfoForm}
        savingInfo={savingInfo}
        onSaveGroupInfo={handleSaveGroupInfo}
        nicknameOpen={nicknameEditOpen}
        onNicknameOpenChange={setNicknameEditOpen}
        nickname={nickname}
        onNicknameChange={setNickname}
        savingNickname={savingNickname}
        onSaveNickname={handleSaveNickname}
        currentUserId={state.userId}
      />
      <ConfirmDialog
        state={confirm}
        onOpenChange={(open) => setConfirm((prev) => ({ ...prev, open }))}
      />
    </AppPage>
  );
}

function InfoItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md bg-slate-50 px-3 py-2 ring-1 ring-slate-100">
      <div className="text-xs text-slate-500">{label}</div>
      <div className="mt-1 truncate text-sm font-medium text-slate-900">{value}</div>
    </div>
  );
}

function InfoText({ title, text }: { title: string; text: string }) {
  return (
    <div>
      <div className="text-xs font-medium text-slate-500">{title}</div>
      <div className="mt-1 whitespace-pre-wrap text-sm leading-6 text-slate-800">{text}</div>
    </div>
  );
}

import { type FormEvent, useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Crown, Loader2, LogOut, Pencil, Shield, ShieldOff, Trash2, UserCog, UserMinus, Users,
} from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { GroupJoinVerification, GroupMemberRole, getErrorText, groupMemberRoleRank, type GroupJoinVerificationValue, type GroupMemberRoleValue } from "im-sdk";
import { AppPage, Surface } from "@/components/AppPage";
import { LoadingState, StateBadge } from "@/components/design-system";
import { shortId } from "@/lib/display-formatters";
import { ConfirmDialog, emptyConfirmDialog, type ConfirmDialogState } from "@/components/ConfirmDialog";

function roleLabel(role: GroupMemberRoleValue): { text: string; className: string } | null {
  if (role === GroupMemberRole.OWNER) return { text: "群主", className: "text-red-500 bg-red-50 border-red-200" };
  if (role === GroupMemberRole.ADMIN) return { text: "管理员", className: "text-blue-500 bg-blue-50 border-blue-200" };
  return null;
}

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
  const [kicking, setKicking] = useState<Record<string, boolean>>({});
  const [roleChanging, setRoleChanging] = useState<Record<string, boolean>>({});
  const [transferring, setTransferring] = useState<Record<string, boolean>>({});
  const [infoEditOpen, setInfoEditOpen] = useState(false);
  const [nicknameEditOpen, setNicknameEditOpen] = useState(false);
  const [savingInfo, setSavingInfo] = useState(false);
  const [savingNickname, setSavingNickname] = useState(false);
  const [confirm, setConfirm] = useState<ConfirmDialogState>(emptyConfirmDialog);
  const [infoForm, setInfoForm] = useState({
    groupName: "",
    faceUrl: "",
    notification: "",
    introduction: "",
    needVerification: GroupJoinVerification.DIRECT as GroupJoinVerificationValue,
  });
  const [nickname, setNickname] = useState("");

  const openConfirm = (next: Omit<ConfirmDialogState, "open">) => {
    setConfirm({ ...next, open: true });
  };

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
  }, [groupId, fetchGroupInfo, fetchGroupMembers]);

  const groupInfo = groupId ? state.groupInfoCache[groupId] : undefined;
  const members = groupId ? state.groupMembers[groupId] || [] : [];
  const currentMember = members.find((m) => m.userId === state.userId);

  // Determine current user's role
  useEffect(() => {
    if (!state.userId) return;
    const me = members.find((m) => m.userId === state.userId);
    if (me) setCurrentUserRole(me.roleLevel);
  }, [members, state.userId]);

  const currentUserRoleRank = groupMemberRoleRank(currentUserRole);
  const isOwner = currentUserRole === GroupMemberRole.OWNER;
  const isAdmin = currentUserRoleRank >= groupMemberRoleRank(GroupMemberRole.ADMIN);
  const canEditGroupInfo = isAdmin;

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

  const refreshGroupManagementState = async () => {
    if (!groupId) return;
    await Promise.all([
      fetchGroupInfo(groupId, { force: true }),
      fetchGroupMembers(groupId, { force: true }),
      fetchConversations(),
    ]);
  };

  const handleKick = async (userId: string) => {
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
  };

  const handleSaveGroupInfo = async (event: FormEvent) => {
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

  const handleSaveNickname = async (event: FormEvent) => {
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

  const handleSetRole = async (memberId: string, roleLevel: GroupMemberRoleValue) => {
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
  };

  const handleTransferOwner = async (memberId: string) => {
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
  };

  const handleDisband = async () => {
    if (!groupId) return;
    setConfirm((prev) => ({ ...prev, loading: true }));
    try {
      await im.group.disband(groupId);
      removeConversationLocal(`group_${groupId}`);
      await refreshAfterMembershipChanged();
      toast("群已解散");
      setConfirm(emptyConfirmDialog);
      navigate("/chat");
    } catch (err) {
      toast(`解散失败：${getErrorText(err)}`);
      setConfirm((prev) => ({ ...prev, loading: false }));
    }
  };

  const handleQuit = async () => {
    if (!groupId) return;
    setConfirm((prev) => ({ ...prev, loading: true }));
    try {
      await quitGroup(groupId);
      toast("已退出群");
      setConfirm(emptyConfirmDialog);
      navigate("/chat");
    } catch (err) {
      toast(`退出失败：${getErrorText(err)}`);
      setConfirm((prev) => ({ ...prev, loading: false }));
    }
  };

  const confirmKick = (memberId: string) => {
    openConfirm({
      title: "移出群成员？",
      description: "该成员会被移出当前群聊，并从会话列表中移除这个群。",
      confirmText: "移出",
      tone: "danger",
      onConfirm: () => handleKick(memberId),
    });
  };

  const confirmSetRole = (memberId: string, roleLevel: GroupMemberRoleValue) => {
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
  };

  const confirmTransferOwner = (memberId: string) => {
    openConfirm({
      title: "转让群主？",
      description: "转让后你会变为普通成员，新群主将拥有群管理权限。",
      confirmText: "转让群主",
      tone: "warning",
      onConfirm: () => handleTransferOwner(memberId),
    });
  };

  const confirmDisband = () => {
    openConfirm({
      title: "解散这个群？",
      description: "解散后所有成员都会失去这个群聊，会话也会被移除。这个操作不可撤销。",
      confirmText: "解散群",
      tone: "danger",
      onConfirm: handleDisband,
    });
  };

  const confirmQuit = () => {
    openConfirm({
      title: "退出这个群？",
      description: "退出后你将不再接收这个群的消息，群会话会从你的列表中移除。",
      confirmText: "退出群",
      tone: "danger",
      onConfirm: handleQuit,
    });
  };

  if (loading) {
    return <LoadingState text="正在读取群资料" />;
  }

  if (loadError) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="text-center">
          <p className="text-sm text-slate-600">加载群信息失败：{loadError}</p>
          <Button variant="outline" className="mt-3" onClick={() => navigate("/chat")}>返回聊天</Button>
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
    <AppPage title="群信息" description={groupInfo.groupName} onBack={() => navigate("/chat")}>
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
                  <h2 className="truncate text-lg font-semibold text-[var(--text-strong)]">{groupInfo.groupName}</h2>
                  <p className="mt-1 truncate text-sm text-[var(--text-muted)]" title={groupInfo.groupId}>ID: {shortId(groupInfo.groupId)}</p>
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

          <Surface>
            <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
              <div>
                <div className="text-sm font-semibold text-slate-900">成员列表</div>
                <div className="text-xs text-slate-500">{members.length} 位成员</div>
              </div>
            </div>
            <div className="divide-y divide-slate-100">
            {members.map((member) => {
              const label = roleLabel(member.roleLevel);
              const canKick =
                isOwner || (isAdmin && groupMemberRoleRank(member.roleLevel) < groupMemberRoleRank(GroupMemberRole.ADMIN));
              const canManageRole = isOwner && member.userId !== state.userId && member.roleLevel !== GroupMemberRole.OWNER;
              const canTransferOwner = isOwner && member.userId !== state.userId && member.roleLevel !== GroupMemberRole.OWNER;
              return (
                <div
                  key={member.userId}
                  role="button"
                  tabIndex={0}
                  className="flex w-full cursor-pointer items-center justify-between gap-3 px-4 py-3 text-left transition-colors hover:bg-slate-50"
                  onClick={() => navigate(`/chat/user/${member.userId}`)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      navigate(`/chat/user/${member.userId}`);
                    }
                  }}
                >
                  <div className="flex min-w-0 items-center gap-3">
                    <Avatar className="h-10 w-10 border border-white shadow-sm">
                      <AvatarImage src={member.faceUrl} alt={member.nickname || member.userId} />
                      <AvatarFallback className="bg-slate-100 text-sm font-semibold text-slate-700">
                        {(member.nickname || member.userId).charAt(0).toUpperCase()}
                      </AvatarFallback>
                    </Avatar>
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="truncate text-sm font-semibold text-slate-900" title={member.nickname || member.userId}>
                          {member.nickname || shortId(member.userId)}
                        </span>
                        {member.roleLevel === GroupMemberRole.OWNER && <Crown className="h-3.5 w-3.5 text-red-500" />}
                        {label && (
                          <span className={`rounded border px-1.5 text-[10px] ${label.className}`}>
                            {label.text}
                          </span>
                        )}
                      </div>
                      <div className="truncate text-xs text-slate-500" title={member.userId}>ID: {shortId(member.userId)}</div>
                    </div>
                  </div>

                  {member.userId !== state.userId && (
                    <div className="flex shrink-0 flex-wrap items-center justify-end gap-1.5">
                      {canManageRole && (
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-slate-600 hover:bg-slate-100"
                          onClick={(event) => {
                            event.stopPropagation();
                            const nextRole = member.roleLevel === GroupMemberRole.ADMIN
                              ? GroupMemberRole.MEMBER
                              : GroupMemberRole.ADMIN;
                            confirmSetRole(member.userId, nextRole);
                          }}
                          disabled={!!roleChanging[member.userId]}
                        >
                          {roleChanging[member.userId] ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : member.roleLevel === GroupMemberRole.ADMIN ? (
                            <ShieldOff className="h-3.5 w-3.5" />
                          ) : (
                            <Shield className="h-3.5 w-3.5" />
                          )}
                        </Button>
                      )}
                      {canTransferOwner && (
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-amber-700 hover:bg-amber-50"
                          onClick={(event) => {
                            event.stopPropagation();
                            confirmTransferOwner(member.userId);
                          }}
                          disabled={!!transferring[member.userId]}
                        >
                          {transferring[member.userId] ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            <UserCog className="h-3.5 w-3.5" />
                          )}
                        </Button>
                      )}
                      {canKick && (
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-red-600 hover:bg-red-50 hover:text-red-700"
                          onClick={(event) => {
                            event.stopPropagation();
                            confirmKick(member.userId);
                          }}
                          disabled={!!kicking[member.userId]}
                        >
                          {kicking[member.userId] ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          ) : (
                            <UserMinus className="h-3.5 w-3.5" />
                          )}
                        </Button>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
            </div>
          </Surface>

          <Surface className="p-4">
            <div className="mb-3 text-sm font-semibold text-red-700">危险操作</div>
            <div className="space-y-2">
              {isOwner && (
                <Button
                  variant="destructive"
                  className="w-full justify-start"
                  onClick={confirmDisband}
                >
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
      <Dialog open={infoEditOpen} onOpenChange={setInfoEditOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>编辑群资料</DialogTitle>
            <DialogDescription>群名称、公告和入群方式会同步给群成员。</DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={handleSaveGroupInfo}>
            <Field label="群名称">
              <Input
                value={infoForm.groupName}
                onChange={(event) => setInfoForm((prev) => ({ ...prev, groupName: event.target.value }))}
                maxLength={40}
              />
            </Field>
            <Field label="群头像 URL">
              <Input
                value={infoForm.faceUrl}
                onChange={(event) => setInfoForm((prev) => ({ ...prev, faceUrl: event.target.value }))}
                placeholder="https://..."
              />
            </Field>
            <Field label="入群方式">
              <select
                className="h-10 w-full rounded-md border border-slate-200 bg-white px-3 text-sm outline-none transition-colors focus:border-slate-400"
                value={infoForm.needVerification}
                onChange={(event) => setInfoForm((prev) => ({
                  ...prev,
                  needVerification: event.target.value as GroupJoinVerificationValue,
                }))}
              >
                <option value={GroupJoinVerification.DIRECT}>可直接加入</option>
                <option value={GroupJoinVerification.NEED_APPROVAL}>需要审批</option>
                <option value={GroupJoinVerification.INVITE_ONLY}>仅邀请入群</option>
                <option value={GroupJoinVerification.FORBIDDEN}>禁止加入</option>
              </select>
            </Field>
            <Field label="群公告">
              <textarea
                className="min-h-20 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm outline-none transition-colors focus:border-slate-400"
                value={infoForm.notification}
                onChange={(event) => setInfoForm((prev) => ({ ...prev, notification: event.target.value }))}
                maxLength={500}
              />
            </Field>
            <Field label="群简介">
              <textarea
                className="min-h-20 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm outline-none transition-colors focus:border-slate-400"
                value={infoForm.introduction}
                onChange={(event) => setInfoForm((prev) => ({ ...prev, introduction: event.target.value }))}
                maxLength={500}
              />
            </Field>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setInfoEditOpen(false)}>取消</Button>
              <Button type="submit" disabled={savingInfo}>
                {savingInfo && <Loader2 className="h-4 w-4 animate-spin" />}
                保存
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog open={nicknameEditOpen} onOpenChange={setNicknameEditOpen}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>修改群昵称</DialogTitle>
            <DialogDescription>这个昵称只在当前群聊中使用。</DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={handleSaveNickname}>
            <Field label="我的群昵称">
              <Input
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
                maxLength={30}
                placeholder={state.userId || "请输入群昵称"}
              />
            </Field>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setNicknameEditOpen(false)}>取消</Button>
              <Button type="submit" disabled={savingNickname}>
                {savingNickname && <Loader2 className="h-4 w-4 animate-spin" />}
                保存
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      <ConfirmDialog
        state={confirm}
        onOpenChange={(open) => setConfirm((prev) => ({ ...prev, open }))}
      />
    </AppPage>
  );
}

function roleText(role: GroupMemberRoleValue): string {
  if (role === GroupMemberRole.OWNER) return "群主";
  if (role === GroupMemberRole.ADMIN) return "管理员";
  return "成员";
}

function joinPolicyText(policy?: string): string {
  if (policy === GroupJoinVerification.NEED_APPROVAL) return "需要审批";
  if (policy === GroupJoinVerification.INVITE_ONLY) return "仅邀请入群";
  if (policy === GroupJoinVerification.FORBIDDEN) return "禁止加入";
  return "可直接加入";
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium text-slate-500">{label}</span>
      {children}
    </label>
  );
}

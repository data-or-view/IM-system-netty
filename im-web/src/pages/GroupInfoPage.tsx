import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Crown, Loader2, LogOut, Trash2, UserMinus, Users,
} from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { GroupJoinVerification, GroupMemberRole, getErrorText, groupMemberRoleRank, type GroupMemberRoleValue } from "im-sdk";
import { AppPage, Surface } from "@/components/AppPage";

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
    dispatch,
    fetchGroupMembers,
    fetchGroupInfo,
    quitGroup,
    removeConversationLocal,
    refreshAfterMembershipChanged,
  } = useStore();
  const [currentUserRole, setCurrentUserRole] = useState<GroupMemberRoleValue>(GroupMemberRole.MEMBER);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [kicking, setKicking] = useState<Record<string, boolean>>({});

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

  useEffect(() => {
    for (const member of members) {
      dispatch({
        type: "SET_USER_PROFILE",
        userId: member.userId,
        info: {
          userId: member.userId,
          nickname: member.nickname,
          faceUrl: member.faceUrl,
        },
      });
    }
  }, [dispatch, members]);

  // Determine current user's role
  useEffect(() => {
    if (!state.userId) return;
    const me = members.find((m) => m.userId === state.userId);
    if (me) setCurrentUserRole(me.roleLevel);
  }, [members, state.userId]);

  const currentUserRoleRank = groupMemberRoleRank(currentUserRole);
  const isOwner = currentUserRole === GroupMemberRole.OWNER;
  const isAdmin = currentUserRoleRank >= groupMemberRoleRank(GroupMemberRole.ADMIN);

  const handleKick = async (userId: string) => {
    if (!groupId) return;
    setKicking((prev) => ({ ...prev, [userId]: true }));
    try {
      await im.group.kick(groupId, userId);
      await fetchGroupMembers(groupId);
      toast("已踢出");
    } catch (err) {
      toast(`踢出失败：${getErrorText(err)}`);
    } finally {
      setKicking((prev) => ({ ...prev, [userId]: false }));
    }
  };

  const handleDisband = async () => {
    if (!groupId) return;
    try {
      await im.group.disband(groupId);
      removeConversationLocal(`group_${groupId}`);
      await refreshAfterMembershipChanged();
      toast("群已解散");
      navigate("/chat");
    } catch (err) {
      toast(`解散失败：${getErrorText(err)}`);
    }
  };

  const handleQuit = async () => {
    if (!groupId) return;
    try {
      await quitGroup(groupId);
      toast("已退出群");
      navigate("/chat");
    } catch (err) {
      toast(`退出失败：${getErrorText(err)}`);
    }
  };

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-slate-500" />
      </div>
    );
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
            <div className="bg-slate-900 px-5 py-5 text-white">
              <div className="flex items-center gap-4">
                <Avatar className="h-16 w-16 border border-white/20 shadow-xl">
                  <AvatarImage src={groupInfo.faceUrl} alt={groupInfo.groupName} />
                  <AvatarFallback className="bg-white/10 text-xl font-semibold text-white">
                    {groupInfo.groupName.charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div className="min-w-0">
                  <h2 className="truncate text-lg font-semibold">{groupInfo.groupName}</h2>
                  <p className="mt-1 truncate text-sm text-white/65">ID: {groupInfo.groupId}</p>
                  <div className="mt-2 inline-flex items-center gap-1.5 rounded-full bg-white/10 px-2.5 py-1 text-xs text-white/80">
                    <Users className="h-3.5 w-3.5" />
                    {groupInfo.memberCount || members.length} 人
                  </div>
                </div>
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
                        <span className="truncate text-sm font-semibold text-slate-900">
                          {member.nickname || member.userId}
                        </span>
                        {member.roleLevel === GroupMemberRole.OWNER && <Crown className="h-3.5 w-3.5 text-red-500" />}
                        {label && (
                          <span className={`rounded border px-1.5 text-[10px] ${label.className}`}>
                            {label.text}
                          </span>
                        )}
                      </div>
                      <div className="truncate text-xs text-slate-500">ID: {member.userId}</div>
                    </div>
                  </div>

                  {canKick && member.userId !== state.userId && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="shrink-0 text-red-600 hover:bg-red-50 hover:text-red-700"
                      onClick={(event) => {
                        event.stopPropagation();
                        void handleKick(member.userId);
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
              );
            })}
            </div>
          </Surface>

          <Surface className="p-4">
            <div className="mb-3 text-sm font-semibold text-slate-900">群操作</div>
            <div className="space-y-2">
              {isOwner && (
                <Button
                  variant="destructive"
                  className="w-full justify-start"
                  onClick={handleDisband}
                >
                  <Trash2 className="h-4 w-4" />
                  解散群
                </Button>
              )}
              {!isOwner && (
                <Button
                  variant="outline"
                  className="w-full justify-start text-red-600 hover:border-red-200 hover:bg-red-50 hover:text-red-700"
                  onClick={handleQuit}
                >
                  <LogOut className="h-4 w-4" />
                  退出群
                </Button>
              )}
            </div>
          </Surface>
          </div>
      </ScrollArea>
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
    <div className="rounded-md bg-slate-50 px-3 py-2">
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

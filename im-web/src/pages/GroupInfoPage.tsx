import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import {
  ArrowLeft, Crown, UserMinus, Trash2, LogOut, Loader2,
} from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";

function roleLabel(level: number): { text: string; className: string } | null {
  if (level >= 200) return { text: "群主", className: "text-red-500 bg-red-50 border-red-200" };
  if (level >= 100) return { text: "管理员", className: "text-blue-500 bg-blue-50 border-blue-200" };
  return null;
}

export default function GroupInfoPage() {
  const { groupId } = useParams<{ groupId: string }>();
  const navigate = useNavigate();
  const { state, fetchGroupMembers, fetchGroupInfo } = useStore();
  const [currentUserRole, setCurrentUserRole] = useState<number>(1);
  const [loading, setLoading] = useState(true);
  const [kicking, setKicking] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (!groupId) return;
    Promise.all([
      fetchGroupInfo(groupId),
      fetchGroupMembers(groupId),
    ]).then(() => setLoading(false));
  }, [groupId, fetchGroupInfo, fetchGroupMembers]);

  const groupInfo = groupId ? state.groupInfoCache[groupId] : undefined;
  const members = groupId ? state.groupMembers[groupId] || [] : [];

  // Determine current user's role
  useEffect(() => {
    if (!state.userId) return;
    const me = members.find((m) => m.userId === state.userId);
    if (me) setCurrentUserRole(me.roleLevel);
  }, [members, state.userId]);

  const isOwner = currentUserRole >= 200;
  const isAdmin = currentUserRole >= 100;

  const handleKick = async (userId: string) => {
    if (!groupId) return;
    setKicking((prev) => ({ ...prev, [userId]: true }));
    try {
      await im.group.kick(groupId, userId);
      await fetchGroupMembers(groupId);
      toast("已踢出");
    } catch {
      toast("踢出失败");
    } finally {
      setKicking((prev) => ({ ...prev, [userId]: false }));
    }
  };

  const handleDisband = async () => {
    if (!groupId) return;
    try {
      await im.group.disband(groupId);
      toast("群已解散");
      navigate("/chat");
    } catch {
      toast("解散失败");
    }
  };

  const handleQuit = async () => {
    if (!groupId) return;
    try {
      await im.group.quit(groupId);
      toast("已退出群");
      navigate("/chat");
    } catch {
      toast("退出失败");
    }
  };

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!groupInfo) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-sm text-muted-foreground">群不存在</p>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col">
      {/* Header */}
      <div className="flex items-center gap-3 border-b px-4 py-3">
        <Button variant="ghost" size="icon" onClick={() => navigate("/chat")}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <div>
          <div className="text-sm font-medium">群信息</div>
          <div className="text-xs text-muted-foreground">{groupInfo.groupName}</div>
        </div>
      </div>

      <ScrollArea className="flex-1 p-4">
        {/* Group basic info */}
        <div className="flex flex-col items-center py-6">
          <Avatar className="mb-3 h-16 w-16">
            <AvatarFallback className="text-lg">
              {groupInfo.groupName.charAt(0).toUpperCase()}
            </AvatarFallback>
          </Avatar>
          <h2 className="text-lg font-semibold">{groupInfo.groupName}</h2>
          <p className="text-xs text-muted-foreground">ID: {groupInfo.groupId}</p>
          <p className="mt-1 text-xs text-muted-foreground">
            群主: {groupInfo.ownerUserId} · {groupInfo.memberCount || members.length} 人
          </p>
        </div>

        <Separator />

        {/* Member list */}
        <div className="py-3">
          <h3 className="mb-2 text-sm font-medium">成员列表（{members.length}）</h3>
          <div className="space-y-1">
            {members.map((member) => {
              const label = roleLabel(member.roleLevel);
              const canKick =
                isOwner || (isAdmin && member.roleLevel < 100);
              return (
                <div
                  key={member.userId}
                  className="flex items-center justify-between rounded-lg px-3 py-2 transition-colors hover:bg-accent"
                >
                  <div className="flex items-center gap-3">
                    <Avatar className="h-9 w-9">
                      <AvatarFallback className="text-xs">
                        {(member.nickname || member.userId).charAt(0).toUpperCase()}
                      </AvatarFallback>
                    </Avatar>
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium">
                          {member.nickname || member.userId}
                        </span>
                        {member.roleLevel >= 200 && <Crown className="h-3.5 w-3.5 text-red-500" />}
                        {label && (
                          <span className={`rounded border px-1.5 text-[10px] ${label.className}`}>
                            {label.text}
                          </span>
                        )}
                      </div>
                      <div className="text-xs text-muted-foreground">ID: {member.userId}</div>
                    </div>
                  </div>

                  {canKick && member.userId !== state.userId && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-destructive"
                      onClick={() => handleKick(member.userId)}
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
        </div>

        <Separator />

        {/* Group actions */}
        <div className="space-y-2 py-4">
          {isOwner && (
            <Button
              variant="destructive"
              className="w-full"
              onClick={handleDisband}
            >
              <Trash2 className="mr-2 h-4 w-4" />
              解散群
            </Button>
          )}
          {!isOwner && (
            <Button
              variant="outline"
              className="w-full text-destructive"
              onClick={handleQuit}
            >
              <LogOut className="mr-2 h-4 w-4" />
              退出群
            </Button>
          )}
        </div>
      </ScrollArea>
    </div>
  );
}

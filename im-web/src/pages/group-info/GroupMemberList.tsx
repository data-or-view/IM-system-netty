import {
  Crown,
  Loader2,
  Shield,
  ShieldOff,
  UserCog,
  UserMinus,
} from "lucide-react";
import {
  GroupMemberRole,
  groupMemberRoleRank,
  type GroupMemberRoleValue,
} from "im-sdk";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Surface } from "@/components/AppPage";
import { shortId } from "@/lib/display-formatters";
import type { GroupMember } from "@/store/store-types";
import { roleLabel } from "@/pages/group-info/group-info-utils";

interface GroupMemberListProps {
  members: GroupMember[];
  currentUserId: string | null;
  isOwner: boolean;
  isAdmin: boolean;
  kicking: Record<string, boolean>;
  roleChanging: Record<string, boolean>;
  transferring: Record<string, boolean>;
  onOpenUser: (userId: string) => void;
  onKick: (userId: string) => void;
  onSetRole: (userId: string, role: GroupMemberRoleValue) => void;
  onTransferOwner: (userId: string) => void;
}

export function GroupMemberList({
  members,
  currentUserId,
  isOwner,
  isAdmin,
  kicking,
  roleChanging,
  transferring,
  onOpenUser,
  onKick,
  onSetRole,
  onTransferOwner,
}: GroupMemberListProps) {
  return (
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
            isOwner ||
            (isAdmin && groupMemberRoleRank(member.roleLevel) < groupMemberRoleRank(GroupMemberRole.ADMIN));
          const canManageRole =
            isOwner && member.userId !== currentUserId && member.roleLevel !== GroupMemberRole.OWNER;
          const canTransferOwner =
            isOwner && member.userId !== currentUserId && member.roleLevel !== GroupMemberRole.OWNER;

          return (
            <div
              key={member.userId}
              role="button"
              tabIndex={0}
              className="flex w-full cursor-pointer items-center justify-between gap-3 px-4 py-3 text-left transition-colors hover:bg-slate-50"
              onClick={() => onOpenUser(member.userId)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onOpenUser(member.userId);
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
                    <span
                      className="truncate text-sm font-semibold text-slate-900"
                      title={member.nickname || member.userId}
                    >
                      {member.nickname || shortId(member.userId)}
                    </span>
                    {member.roleLevel === GroupMemberRole.OWNER && (
                      <Crown className="h-3.5 w-3.5 text-red-500" />
                    )}
                    {label && (
                      <span className={`rounded border px-1.5 text-[10px] ${label.className}`}>
                        {label.text}
                      </span>
                    )}
                  </div>
                  <div className="truncate text-xs text-slate-500" title={member.userId}>
                    ID: {shortId(member.userId)}
                  </div>
                </div>
              </div>

              {member.userId !== currentUserId && (
                <div className="flex shrink-0 flex-wrap items-center justify-end gap-1.5">
                  {canManageRole && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-slate-600 hover:bg-slate-100"
                      onClick={(event) => {
                        event.stopPropagation();
                        const nextRole =
                          member.roleLevel === GroupMemberRole.ADMIN
                            ? GroupMemberRole.MEMBER
                            : GroupMemberRole.ADMIN;
                        onSetRole(member.userId, nextRole);
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
                        onTransferOwner(member.userId);
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
                        onKick(member.userId);
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
  );
}

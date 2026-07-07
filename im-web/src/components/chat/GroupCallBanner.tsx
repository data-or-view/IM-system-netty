import { Button } from "@/components/ui/button";
import { StateBadge, StatusDot } from "@/components/design-system";
import { PhoneOff } from "lucide-react";
import type { GroupCallSession } from "im-sdk";
import type { GroupMember } from "@/store/store";

interface GroupCallBannerProps {
  activeGroupCall: GroupCallSession | null;
  groupMembers: GroupMember[];
  canEndActiveGroupCall: boolean;
  groupCallBusy: boolean;
  onEndGroupCall: () => void;
  onJoinGroupCall: () => void;
}

export default function GroupCallBanner({
  activeGroupCall,
  groupMembers,
  canEndActiveGroupCall,
  groupCallBusy,
  onEndGroupCall,
  onJoinGroupCall,
}: GroupCallBannerProps) {
  if (!activeGroupCall) return null;

  return (
    <div className="border-b border-blue-100 bg-blue-50 px-5 py-2">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-sm font-semibold text-blue-900">
            <StatusDot tone="online" pulse />
            群视频进行中
            <StateBadge tone="online">
              {activeGroupCall.participantCount ?? activeGroupCall.participants?.length ?? 1} 人
            </StateBadge>
          </div>
          <div className="mt-0.5 truncate text-xs text-blue-600/80">
            发起人 {displayGroupCallUser(activeGroupCall.initiatorUserId, groupMembers)} ·{" "}
            {formatGroupCallStartedAt(activeGroupCall.startedAt)}
          </div>
        </div>
        <div className="flex items-center gap-2">
          {canEndActiveGroupCall && (
            <Button
              size="sm"
              variant="outline"
              className="h-8 border-red-200 bg-white text-red-700 hover:bg-red-50"
              onClick={onEndGroupCall}
              disabled={groupCallBusy}
            >
              <PhoneOff className="h-3.5 w-3.5" />
              结束
            </Button>
          )}
          <Button
            size="sm"
            className="h-8 bg-blue-600 text-white shadow-sm hover:bg-blue-700 hover:text-white disabled:bg-blue-400 disabled:text-white"
            onClick={onJoinGroupCall}
            disabled={groupCallBusy}
          >
            {groupCallBusy ? "处理中" : "加入"}
          </Button>
        </div>
      </div>
    </div>
  );
}

function formatGroupCallStartedAt(ts?: number): string {
  if (!ts) return "刚刚开始";
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")} 开始`;
}

function displayGroupCallUser(
  userId: string | undefined,
  members: Array<{ userId: string; nickname?: string }>
): string {
  if (!userId) return "未知";
  const member = members.find((item) => item.userId === userId);
  return member?.nickname || userId;
}

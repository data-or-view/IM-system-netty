import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { PageHeader, StatusDot } from "@/components/design-system";
import { ConversationType, type GroupCallSession } from "im-sdk";
import { Info, Phone, Video } from "lucide-react";
import type { Conversation } from "@/store/store";

interface ChatHeaderProps {
  conversation: Conversation | undefined;
  activeGroupCall: GroupCallSession | null;
  groupCallBusy: boolean;
  onShowInfo: () => void;
  onStartCall: (callType: "voice" | "video") => void;
  onStartGroupCall: () => void;
  onJoinGroupCall: () => void;
}

export default function ChatHeader({
  conversation,
  activeGroupCall,
  groupCallBusy,
  onShowInfo,
  onStartCall,
  onStartGroupCall,
  onJoinGroupCall,
}: ChatHeaderProps) {
  return (
    <PageHeader
      icon={
        <Avatar className="h-8 w-8 shadow-sm">
          <AvatarImage src={conversation?.faceUrl} />
          <AvatarFallback className="bg-gradient-to-br from-slate-200 to-slate-300 text-xs text-slate-600">
            {(conversation?.showName || "?").charAt(0).toUpperCase()}
          </AvatarFallback>
        </Avatar>
      }
      title={conversation?.showName || "会话"}
      description={
        <span className="inline-flex items-center gap-1.5">
          <StatusDot tone={conversation?.conversationType === ConversationType.GROUP ? "info" : "online"} />
          {conversation?.conversationType === ConversationType.GROUP ? "群聊" : "单聊"}
        </span>
      }
      actions={
        <>
          {conversation?.conversationType !== ConversationType.GROUP && conversation?.userId && (
            <div className="flex items-center gap-0.5">
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-9 w-9 rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-700"
                aria-label="语音通话"
                title="语音通话"
                onClick={() => onStartCall("voice")}
              >
                <Phone className="h-4 w-4" />
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-9 w-9 rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-700"
                aria-label="视频通话"
                title="视频通话"
                onClick={() => onStartCall("video")}
              >
                <Video className="h-4 w-4" />
              </Button>
            </div>
          )}
          {conversation?.conversationType === ConversationType.GROUP && conversation?.groupId && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-9 w-9 rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-700"
              aria-label={activeGroupCall ? "加入群视频" : "发起群视频"}
              title={activeGroupCall ? "加入群视频" : "发起群视频"}
              onClick={activeGroupCall ? onJoinGroupCall : onStartGroupCall}
              disabled={groupCallBusy}
            >
              <Video className="h-4 w-4" />
            </Button>
          )}
          <button
            type="button"
            onClick={onShowInfo}
            className="flex h-9 w-9 items-center justify-center rounded-lg text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700"
            aria-label="查看资料"
            title="查看资料"
          >
            <Info className="h-4 w-4" />
          </button>
        </>
      }
    />
  );
}

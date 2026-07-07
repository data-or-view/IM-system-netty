import { useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { EmptyState, MessageStatusIcon } from "@/components/design-system";
import {
  MessageContentRenderer,
  compactSystemMessageText,
  isCompactSystemMessage,
} from "@/components/MessageContentRenderer";
import { APP_ROUTES } from "@/config/routes";
import { messageRenderKey } from "@/lib/messages";
import { cn } from "@/lib/utils";
import { MessageCircle, MoreHorizontal, Undo2 } from "lucide-react";
import { ConversationType } from "im-sdk";
import type { Conversation, Message } from "@/store/store";

interface RevokeMessageInput {
  conversationId: string;
  seq: number;
  groupId?: string;
}

interface MessageListProps {
  conversation: Conversation | undefined;
  messages: Message[];
  currentUserId: string | null;
  onRevoke: (message: RevokeMessageInput) => void;
}

export default function MessageList({
  conversation,
  messages,
  currentUserId,
  onRevoke,
}: MessageListProps) {
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  return (
    <ScrollArea className="flex-1 px-4 py-4 md:px-6">
      {messages.length === 0 && (
        <div className="flex h-full min-h-[320px] items-center justify-center">
          <EmptyState
            icon={<MessageCircle className="h-4 w-4" />}
            title="暂无消息"
            description="发送第一条消息开始聊天。"
          />
        </div>
      )}

      <div className="mx-auto max-w-3xl space-y-1 pb-2">
        {messages.map((msg) => (
          <MessageItem
            key={messageRenderKey(msg)}
            conversation={conversation}
            currentUserId={currentUserId}
            message={msg}
            onOpenUser={(userId) => navigate(APP_ROUTES.user(userId))}
            onRevoke={onRevoke}
          />
        ))}
        <div ref={messagesEndRef} />
      </div>
    </ScrollArea>
  );
}

function MessageItem({
  conversation,
  currentUserId,
  message,
  onOpenUser,
  onRevoke,
}: {
  conversation: Conversation | undefined;
  currentUserId: string | null;
  message: Message;
  onOpenUser: (userId: string) => void;
  onRevoke: (message: RevokeMessageInput) => void;
}) {
  const isMine = message.senderUserId === currentUserId;
  const isRevoked = message.contentType === 101 || message.content === "消息已撤回";
  const isCompactSystem = isCompactSystemMessage(message);
  const isPending = message.status === 0 && message.seq <= 0;
  const canRevoke = isMine && message.seq > 0 && !isPending && message.status !== -1;

  if (isRevoked) {
    return (
      <div className="flex justify-center py-1">
        <span className="rounded-full border border-slate-200 bg-white/70 px-4 py-1 text-xs text-slate-400 shadow-sm backdrop-blur-sm">
          {isMine ? "你" : message.senderNickname || message.senderUserId} 撤回了一条消息
        </span>
      </div>
    );
  }

  if (isCompactSystem) {
    return (
      <div className="flex justify-center py-2">
        <div className="line-clamp-2 max-w-[min(78%,40rem)] break-words rounded-full bg-slate-200/70 px-3 py-1 text-center text-[11px] leading-5 text-slate-500">
          {compactSystemMessageText(message)}
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        "msg-in flex items-end gap-2",
        isMine ? "justify-end" : "justify-start"
      )}
    >
      {!isMine && (
        <Avatar
          className="mb-1 h-8 w-8 shrink-0 cursor-pointer shadow-sm"
          onClick={() => onOpenUser(message.senderUserId)}
        >
          <AvatarFallback className="bg-gradient-to-br from-slate-200 to-slate-300 text-[10px] text-slate-600">
            {(message.senderNickname || message.senderUserId).charAt(0).toUpperCase()}
          </AvatarFallback>
        </Avatar>
      )}

      <div className={cn("group flex max-w-[72%] flex-col gap-1", isMine && "items-end")}>
        {!isMine && conversation?.conversationType === ConversationType.GROUP && (
          <span className="pl-1 text-[11px] font-medium text-slate-500">
            {message.senderNickname || message.senderUserId}
          </span>
        )}

        <div className={cn("flex items-end gap-1.5", isMine && "flex-row-reverse")}>
          <div
            className={cn(
              "relative px-3.5 py-2.5 text-sm shadow-sm",
              message.status === -1
                ? "rounded-2xl border border-red-200 bg-red-50 text-red-800"
                : isMine
                  ? "rounded-2xl rounded-br-md bg-gradient-to-br from-blue-500 to-blue-600 text-white"
                  : "rounded-2xl rounded-bl-md border border-slate-200/80 bg-white text-slate-800"
            )}
          >
            <MessageContentRenderer message={message} />
            <div
              className={cn(
                "mt-1 text-[10px] leading-none",
                isMine && message.status !== -1
                  ? "text-white/60"
                  : "text-slate-400"
              )}
            >
              {formatMsgTime(message.createTime)}
              {isMine &&
                (message.status === -1
                  ? " 发送失败"
                  : isPending
                    ? " 发送中…"
                    : "")}
            </div>
          </div>

          {isMine && (
            <MessageStatusIcon status={isPending ? 0 : message.status} errorText={message.errorText} />
          )}

          {canRevoke && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  aria-label="更多消息操作"
                  className="mb-2 flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition-all hover:bg-slate-200 focus:visible focus:opacity-100 md:invisible md:h-6 md:w-6 md:opacity-0 md:group-hover:visible md:group-hover:opacity-100"
                >
                  <MoreHorizontal className="h-3.5 w-3.5" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" side="top">
                <DropdownMenuItem
                  onClick={() =>
                    onRevoke({
                      conversationId: message.conversationId,
                      seq: message.seq,
                      groupId: conversation?.groupId,
                    })
                  }
                >
                  <Undo2 className="mr-2 h-4 w-4" />
                  撤回
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
      </div>
    </div>
  );
}

function formatMsgTime(ts: number): string {
  if (!Number.isFinite(ts)) return "--:--";
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
}

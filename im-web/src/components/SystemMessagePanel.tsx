import { useCallback, useEffect, useMemo, useState } from "react";
import { Bell, Check, Inbox, RefreshCw, UsersRound } from "lucide-react";
import type { SystemMessageInboxItem } from "im-sdk";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { im } from "@/sdk/im-sdk";
import { useStore } from "@/store/store";
import { cn } from "@/lib/utils";

export default function SystemMessagePanel() {
  const { state, refreshSystemMessages } = useStore();
  const [loading, setLoading] = useState(false);
  const messages = state.systemMessages;

  const unreadCount = useMemo(
    () => messages.filter((message) => !message.readAt).length,
    [messages],
  );

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      await refreshSystemMessages();
    } finally {
      setLoading(false);
    }
  }, [refreshSystemMessages]);

  const markRead = useCallback(async (messageId: string) => {
    await im.system.markRead(messageId);
    await refreshSystemMessages();
  }, [refreshSystemMessages]);

  const markAllRead = useCallback(async () => {
    await im.system.markAllRead();
    await refreshSystemMessages();
  }, [refreshSystemMessages]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return (
    <div className="flex h-full flex-1 flex-col bg-slate-50">
      <div className="flex items-center justify-between gap-3 border-b border-slate-200 bg-white/95 px-5 py-3 shadow-sm">
        <div className="flex min-w-0 items-center gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-slate-900 text-white shadow-sm">
            <Bell className="h-5 w-5" />
          </div>
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold">系统通知</div>
            <div className="text-xs text-muted-foreground">
              {unreadCount > 0 ? `${unreadCount} 条未读消息` : "暂无未读消息"}
            </div>
          </div>
        </div>
        <div className="flex shrink-0 gap-2">
          <Button variant="outline" size="sm" className="h-9" onClick={() => void reload()} disabled={loading}>
            <RefreshCw className={cn("h-3.5 w-3.5", loading && "animate-spin")} />
            刷新
          </Button>
          <Button variant="secondary" size="sm" className="h-9" onClick={() => void markAllRead()} disabled={unreadCount === 0}>
            <Check className="h-3.5 w-3.5" />
            全部已读
          </Button>
        </div>
      </div>

      <ScrollArea className="flex-1">
        {messages.length === 0 ? (
          <div className="flex h-full min-h-[360px] flex-col items-center justify-center px-6 text-center">
            <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-md bg-white text-slate-500 shadow-sm ring-1 ring-slate-200">
              <Inbox className="h-5 w-5" />
            </div>
            <div className="text-sm font-medium">暂无系统通知</div>
            <div className="mt-1 max-w-sm text-xs leading-5 text-muted-foreground">
              平台通知、业务提醒和账号消息会以卡片形式显示在这里。
            </div>
          </div>
        ) : (
          <div className="mx-auto flex w-full max-w-3xl flex-col gap-3 px-5 py-5">
            {messages.map((message) => (
              <SystemMessageCard
                key={message.messageId}
                message={message}
                onRead={() => void markRead(message.messageId)}
              />
            ))}
          </div>
        )}
      </ScrollArea>
    </div>
  );
}

function SystemMessageCard({
  message,
  onRead,
}: {
  message: SystemMessageInboxItem;
  onRead: () => void;
}) {
  const unread = !message.readAt;
  const isGroupDisbanded = message.contentType === "group_disbanded";
  return (
    <article className={cn(
      "rounded-md border border-slate-200 bg-white p-4 shadow-sm",
      isGroupDisbanded && "border-amber-200 bg-amber-50/60",
    )}>
      <div className="mb-3 flex items-start justify-between gap-3">
        <div className="flex min-w-0 gap-3">
          {isGroupDisbanded && (
            <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-amber-100 text-amber-700">
              <UsersRound className="h-4 w-4" />
            </div>
          )}
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              {unread && <span className="h-2 w-2 shrink-0 rounded-full bg-primary" />}
              <h3 className="truncate text-sm font-semibold">{message.title}</h3>
            </div>
            <div className="mt-1 text-xs text-muted-foreground">
              {(message.channelName || message.channelId || "系统")} · {formatTime(message.createdAt)}
            </div>
          </div>
        </div>
        {unread && (
          <Button variant="ghost" size="sm" className="h-7 shrink-0 px-2 text-xs" onClick={onRead}>
            标记已读
          </Button>
        )}
      </div>
      {message.summary && (
        <p className="mb-3 text-sm leading-6 text-muted-foreground">{message.summary}</p>
      )}
      <div className="whitespace-pre-wrap rounded-md border border-slate-100 bg-slate-50 px-3 py-2 text-sm leading-6">
        {message.content || "打开详情后会显示完整内容"}
      </div>
    </article>
  );
}

function formatTime(ts?: number): string {
  if (!ts) return "";
  const date = new Date(ts);
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

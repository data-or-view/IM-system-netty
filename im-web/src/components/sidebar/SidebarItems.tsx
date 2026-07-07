import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ConversationType, getErrorText } from "im-sdk";
import { Bell, MoreHorizontal, UserMinus } from "lucide-react";
import { toast } from "sonner";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ConfirmDialog, emptyConfirmDialog, type ConfirmDialogState } from "@/components/ConfirmDialog";
import {
  SYSTEM_CONVERSATION_ID,
  useStore,
  type Conversation,
  type FriendInfo,
  type GroupInfo,
} from "@/store/store";
import { APP_ROUTES } from "@/config/routes";
import { displayText, formatMessagePreview, shortId } from "@/lib/display-formatters";
import { cn } from "@/lib/utils";

export function SystemConversationItem() {
  const { state, dispatch } = useStore();
  const navigate = useNavigate();
  const isActive = state.activeConversationId === SYSTEM_CONVERSATION_ID;
  const latest = state.latestSystemMessage;
  const subtitle = latest?.summary || latest?.title || "平台通知与账号消息";

  return (
    <button
      onClick={() => {
        dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: SYSTEM_CONVERSATION_ID });
        navigate(APP_ROUTES.chat);
      }}
      className={cn(
        "flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors",
        isActive ? "bg-blue-50 text-blue-950" : "hover:bg-slate-50"
      )}
    >
      <div
        className={cn(
          "flex h-10 w-10 shrink-0 items-center justify-center rounded-full",
          isActive ? "bg-blue-100 text-blue-600" : "bg-gradient-to-br from-blue-400 to-indigo-500 text-white"
        )}
      >
        <Bell className="h-4.5 w-4.5" style={{ width: 18, height: 18 }} />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="truncate text-sm font-semibold">系统通知</span>
          {latest?.createdAt && (
            <span className="shrink-0 text-[11px] text-slate-400">{formatTime(latest.createdAt)}</span>
          )}
        </div>
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-xs text-slate-500">{subtitle}</span>
          {state.systemUnreadCount > 0 && <UnreadBadge count={state.systemUnreadCount} />}
        </div>
      </div>
    </button>
  );
}

export function ConversationItem({ conv }: { conv: Conversation }) {
  const { state, dispatch } = useStore();
  const navigate = useNavigate();
  const isActive = state.activeConversationId === conv.conversationId;
  const title = conversationTitle(conv);
  const subtitle =
    formatMessagePreview(conv.latestMsg, conv.conversationType) ||
    (conv.conversationType === ConversationType.GROUP ? "群聊" : "暂无消息");

  return (
    <button
      onClick={() => {
        dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: conv.conversationId });
        navigate(APP_ROUTES.chat);
      }}
      className={cn(
        "relative flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors",
        isActive
          ? "bg-blue-50 before:absolute before:left-0 before:top-3 before:h-[calc(100%-1.5rem)] before:w-0.5 before:rounded-full before:bg-blue-500"
          : "hover:bg-slate-50"
      )}
    >
      <Avatar className="h-10 w-10 shrink-0 shadow-sm">
        <AvatarImage src={conv.faceUrl} />
        <AvatarFallback className="bg-gradient-to-br from-slate-200 to-slate-300 text-slate-600 text-xs">
          {fallbackName(title)}
        </AvatarFallback>
      </Avatar>
      <div className="min-w-0 flex-1 overflow-hidden">
        <div className="flex items-baseline justify-between gap-2">
          <span className="min-w-0 flex-1 truncate text-sm font-semibold text-slate-800">{title}</span>
          {conv.latestMsgSendTime && (
            <span className="shrink-0 text-[11px] text-slate-400">{formatTime(conv.latestMsgSendTime)}</span>
          )}
        </div>
        <div className="flex items-center justify-between gap-2">
          <span className="min-w-0 flex-1 truncate text-xs text-slate-500">{subtitle}</span>
          {conv.unreadCount > 0 && <UnreadBadge count={conv.unreadCount} />}
        </div>
      </div>
    </button>
  );
}

export function FriendItem({ friend }: { friend: FriendInfo }) {
  const { openSingleChat, removeFriend } = useStore();
  const navigate = useNavigate();
  const [confirm, setConfirm] = useState<ConfirmDialogState>(emptyConfirmDialog);
  const displayName = friend.remark || friend.nickname || friend.friendUserId;

  const openChat = () => {
    openSingleChat({
      userId: friend.friendUserId,
      nickname: displayName,
      faceUrl: friend.faceUrl,
    });
    navigate(APP_ROUTES.chat);
  };

  const handleRemoveFriend = async () => {
    setConfirm((prev) => ({ ...prev, loading: true }));
    try {
      await removeFriend(friend.friendUserId);
      toast("已删除好友");
      setConfirm(emptyConfirmDialog);
    } catch (err) {
      toast(`删除失败：${getErrorText(err)}`);
      setConfirm((prev) => ({ ...prev, loading: false }));
    }
  };

  return (
    <div className="group flex items-center gap-3 rounded-lg px-3 py-2.5 transition-colors hover:bg-slate-50">
      <button onClick={openChat} className="flex min-w-0 flex-1 items-center gap-3 text-left">
        <Avatar className="h-9 w-9 shrink-0 shadow-sm">
          <AvatarImage src={friend.faceUrl} />
          <AvatarFallback className="bg-gradient-to-br from-slate-200 to-slate-300 text-slate-600 text-xs">
            {fallbackName(displayName)}
          </AvatarFallback>
        </Avatar>
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-slate-800">{displayName}</div>
          <div className="truncate text-xs text-slate-400" title={friend.friendUserId}>ID: {shortId(friend.friendUserId)}</div>
        </div>
      </button>

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button className="invisible flex h-7 w-7 items-center justify-center rounded-md text-slate-400 opacity-0 transition-all hover:bg-slate-200 hover:text-slate-600 group-hover:visible group-hover:opacity-100">
            <MoreHorizontal className="h-4 w-4" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            className="text-destructive"
            onClick={() => {
              setConfirm({
                open: true,
                title: "删除好友？",
                description: "删除后你们的好友关系会解除，后续发送消息可能受到限制。",
                confirmText: "删除好友",
                tone: "danger",
                onConfirm: handleRemoveFriend,
              });
            }}
          >
            <UserMinus className="mr-2 h-4 w-4" />
            删除好友
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <ConfirmDialog
        state={confirm}
        onOpenChange={(open) => setConfirm((prev) => ({ ...prev, open }))}
      />
    </div>
  );
}

export function GroupItem({ group }: { group: GroupInfo }) {
  const { state, openGroupChat } = useStore();
  const navigate = useNavigate();
  const conversation = useMemo(
    () =>
      state.conversations.find(
        (conv) =>
          conv.conversationType === ConversationType.GROUP &&
          (conv.groupId === group.groupId || conv.conversationId === `group_${group.groupId}`)
      ),
    [group.groupId, state.conversations]
  );

  const openChat = () => {
    openGroupChat({
      groupId: group.groupId,
      groupName: conversation?.showName || groupTitle(group),
      faceUrl: conversation?.faceUrl || group.faceUrl,
    });
    navigate(APP_ROUTES.chat);
  };

  return (
    <button
      onClick={openChat}
      className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-slate-50"
    >
      <Avatar className="h-9 w-9 shrink-0 shadow-sm">
        <AvatarImage src={group.faceUrl} />
        <AvatarFallback className="bg-gradient-to-br from-violet-100 to-violet-200 text-violet-700 text-xs">
          {fallbackName(groupTitle(group))}
        </AvatarFallback>
      </Avatar>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-semibold text-slate-800">{groupTitle(group)}</div>
        <div className="truncate text-xs text-slate-400">
          ID: {shortId(group.groupId)}
          {group.memberCount ? ` · ${group.memberCount} 人` : ""}
        </div>
      </div>
    </button>
  );
}

export function fallbackName(name?: string): string {
  return (displayText(name) || "?").charAt(0).toUpperCase();
}

function UnreadBadge({ count }: { count: number }) {
  return (
    <span className="flex h-5 min-w-[20px] shrink-0 items-center justify-center rounded-full bg-red-500 px-1.5 text-[10px] font-bold leading-none text-white">
      {count > 99 ? "99+" : count}
    </span>
  );
}

function conversationTitle(conv: Conversation): string {
  return (
    displayText(conv.showName) ??
    displayText(conv.groupName) ??
    displayText(conv.userId) ??
    displayText(conv.groupId) ??
    (conv.conversationType === ConversationType.GROUP ? "未命名群聊" : "未知用户")
  );
}

function groupTitle(group: GroupInfo): string {
  return displayText(group.groupName) ?? displayText(group.groupId) ?? "未命名群聊";
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const diff = now.getTime() - d.getTime();
  if (diff < 60_000) return "刚刚";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`;
  if (
    d.getDate() === now.getDate() &&
    d.getMonth() === now.getMonth() &&
    d.getFullYear() === now.getFullYear()
  ) {
    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  }
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

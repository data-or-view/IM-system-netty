import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  SYSTEM_CONVERSATION_ID,
  useStore,
  type Conversation,
  type FriendInfo,
  type GroupInfo,
} from "@/store/store";
import {
  ConversationType,
  getErrorText,
} from "im-sdk";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import {
  Tooltip,
  TooltipTrigger,
  TooltipContent,
  TooltipProvider,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { EmptyState, StatusDot } from "@/components/design-system";
import {
  MessageCircle,
  Users,
  UserPlus,
  Plus,
  MoreHorizontal,
  UserMinus,
  Contact,
  Bell,
  User,
  Search,
  LogOut,
} from "lucide-react";
import UserSearchDialog from "./sidebar/UserSearchDialog";
import GroupSearchDialog from "./sidebar/GroupSearchDialog";
import FriendRequestDialog from "./sidebar/FriendRequestDialog";
import GroupRequestDialog from "./sidebar/GroupRequestDialog";
import { toast } from "sonner";
import { displayText, formatMessagePreview, shortId } from "@/lib/display-formatters";
import { ConfirmDialog, emptyConfirmDialog, type ConfirmDialogState } from "@/components/ConfirmDialog";
import { APP_ROUTES } from "@/config/routes";

type Tab = "chats" | "friends" | "groups";

export default function Sidebar() {
  const { state, logout } = useStore();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("chats");
  const [searchUserOpen, setSearchUserOpen] = useState(false);
  const [searchGroupOpen, setSearchGroupOpen] = useState(false);
  const [friendRequestOpen, setFriendRequestOpen] = useState(false);
  const [groupRequestOpen, setGroupRequestOpen] = useState(false);

  const currentUser = state.userId ? state.userProfileCache[state.userId] : undefined;
  const currentDisplayName =
    displayText(currentUser?.nickname) ?? displayText(state.userId) ?? "未登录";

  const chatUnread =
    state.conversations.reduce((sum, c) => sum + (c.unreadCount || 0), 0) +
    state.systemUnreadCount;

  return (
    <TooltipProvider>
      {/*
       * Outer shell:
       *   mobile  → column (tab header on top, list below, limited height)
       *   desktop → row    (icon rail | content list)
       */}
      <div className="flex h-[44vh] min-h-[280px] w-full shrink-0 flex-col border-b border-slate-200/80 md:h-full md:w-[18.5rem] md:flex-row md:border-b-0 md:border-r">

        {/* ─────────────────────────────────────────
            Desktop icon rail  (64 px, dark navy)
        ───────────────────────────────────────── */}
        <div className="hidden md:flex md:w-16 md:min-w-16 md:max-w-16 md:shrink-0 md:grow-0 md:flex-col md:items-center md:bg-[#1a1c2a] md:pb-5 md:pt-5">

          {/* User avatar with online indicator */}
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                onClick={() => state.userId && navigate(APP_ROUTES.user(state.userId))}
                className="relative mb-7 flex-none rounded-full transition-opacity hover:opacity-90"
              >
                <Avatar className="h-10 w-10 ring-2 ring-white/[0.09] ring-offset-2 ring-offset-[#1a1c2a]">
                  <AvatarImage src={currentUser?.faceUrl} />
                  <AvatarFallback className="bg-gradient-to-br from-blue-500 to-violet-600 text-sm text-white">
                    {state.userId ? fallbackName(currentDisplayName) : <User className="h-4 w-4" />}
                  </AvatarFallback>
                </Avatar>
                <span
                  className={cn(
                    "absolute bottom-0 right-0 h-3 w-3 rounded-full border-2 border-[#1a1c2a]",
                    state.connected ? "bg-emerald-400" : "bg-slate-600"
                  )}
                />
              </button>
            </TooltipTrigger>
            <TooltipContent side="right" className="text-xs">
              {currentDisplayName} · {state.connected ? "在线" : "离线"}
            </TooltipContent>
          </Tooltip>

          {/* Nav tabs */}
          <div className="flex w-11 flex-col gap-1">
            <RailTab
              icon={<MessageCircle className="h-5 w-5" />}
              label="消息"
              active={tab === "chats"}
              badge={chatUnread}
              onClick={() => setTab("chats")}
            />
            <RailTab
              icon={<Contact className="h-5 w-5" />}
              label="联系人"
              active={tab === "friends"}
              badge={state.unhandledApplyCount}
              onClick={() => setTab("friends")}
            />
            <RailTab
              icon={<Users className="h-5 w-5" />}
              label="群组"
              active={tab === "groups"}
              badge={state.unhandledGroupApplyCount}
              onClick={() => setTab("groups")}
            />
          </div>

          <div className="flex-1" />

          {/* Bottom action icons */}
          <div className="flex w-11 flex-col items-center gap-1">
            <RailAction
              icon={<UserPlus className="h-[18px] w-[18px]" />}
              label="添加好友"
              onClick={() => setSearchUserOpen(true)}
            />
            <RailAction
              icon={<Plus className="h-[18px] w-[18px]" />}
              label="创建群组"
              onClick={() => navigate(APP_ROUTES.createGroup)}
            />
            <RailAction
              icon={<LogOut className="h-[18px] w-[18px]" />}
              label="退出登录"
              onClick={() => void logout()}
            />
          </div>
        </div>

        {/* ─────────────────────────────────────────
            Mobile compact header
        ───────────────────────────────────────── */}
        <div className="flex items-center justify-between border-b border-slate-200 bg-white px-3 py-2.5 md:hidden">
          <button
            className="flex min-w-0 flex-1 items-center gap-2.5 text-left"
            onClick={() => state.userId && navigate(APP_ROUTES.user(state.userId))}
          >
            <Avatar className="h-8 w-8 shrink-0">
              <AvatarImage src={currentUser?.faceUrl} />
              <AvatarFallback className="bg-gradient-to-br from-blue-500 to-violet-600 text-xs text-white">
                {state.userId ? fallbackName(currentDisplayName) : <User className="h-3 w-3" />}
              </AvatarFallback>
            </Avatar>
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold text-slate-800">
                {currentDisplayName}
              </div>
              <div className="flex items-center gap-1 text-[10px] text-slate-500">
                <StatusDot tone={state.connected ? "online" : "offline"} />
                {state.connected ? "已连接" : "未连接"}
              </div>
            </div>
          </button>

          <div className="flex shrink-0 items-center gap-0.5">
            <MobileTabIcon
              active={tab === "chats"}
              badge={chatUnread}
              onClick={() => setTab("chats")}
            >
              <MessageCircle className="h-5 w-5" />
            </MobileTabIcon>
            <MobileTabIcon
              active={tab === "friends"}
              badge={state.unhandledApplyCount}
              onClick={() => setTab("friends")}
            >
              <Contact className="h-5 w-5" />
            </MobileTabIcon>
            <MobileTabIcon
              active={tab === "groups"}
              badge={state.unhandledGroupApplyCount}
              onClick={() => setTab("groups")}
            >
              <Users className="h-5 w-5" />
            </MobileTabIcon>
          </div>
        </div>

        {/* ─────────────────────────────────────────
            Content panel (white, scrollable list)
        ───────────────────────────────────────── */}
        <div className="flex min-h-0 min-w-0 flex-1 flex-col bg-white">
          {/* Section label — desktop only */}
          <div className="hidden border-b border-slate-100 px-4 py-3 md:block">
            <p className="text-[11px] font-semibold uppercase tracking-widest text-slate-400">
              {tab === "chats" ? "消息" : tab === "friends" ? "联系人" : "群组"}
            </p>
          </div>

          {tab === "chats" && <ChatList />}
          {tab === "friends" && (
            <FriendList
              onSearchUser={() => setSearchUserOpen(true)}
              onFriendRequests={() => setFriendRequestOpen(true)}
            />
          )}
          {tab === "groups" && (
            <GroupList
              onSearchGroup={() => setSearchGroupOpen(true)}
              onCreateGroup={() => navigate(APP_ROUTES.createGroup)}
              onGroupRequests={() => setGroupRequestOpen(true)}
            />
          )}
        </div>
      </div>

      <UserSearchDialog open={searchUserOpen} onOpenChange={setSearchUserOpen} />
      <GroupSearchDialog open={searchGroupOpen} onOpenChange={setSearchGroupOpen} />
      <FriendRequestDialog open={friendRequestOpen} onOpenChange={setFriendRequestOpen} />
      <GroupRequestDialog open={groupRequestOpen} onOpenChange={setGroupRequestOpen} />
    </TooltipProvider>
  );
}

/* ── Icon rail components ── */

function RailTab({
  icon,
  label,
  active,
  badge,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  active: boolean;
  badge?: number;
  onClick: () => void;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          onClick={onClick}
          className={cn(
            "relative flex h-11 w-11 items-center justify-center rounded-xl transition-colors duration-150",
            active
              ? "bg-white/[0.13] text-white"
              : "text-white/40 hover:bg-white/[0.07] hover:text-white/70"
          )}
        >
          {icon}
          {active && (
            <span className="absolute bottom-1.5 h-[3px] w-[3px] rounded-full bg-blue-400" />
          )}
          {(badge ?? 0) > 0 && (
            <span className="absolute right-1 top-1 flex h-[15px] min-w-[15px] items-center justify-center rounded-full bg-red-500 px-0.5 text-[9px] font-bold leading-none text-white">
              {(badge ?? 0) > 99 ? "99+" : badge}
            </span>
          )}
        </button>
      </TooltipTrigger>
      <TooltipContent side="right" className="text-xs">
        {label}
      </TooltipContent>
    </Tooltip>
  );
}

function RailAction({
  icon,
  label,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          onClick={onClick}
          className="flex h-9 w-9 items-center justify-center rounded-lg text-white/35 transition-all duration-150 hover:bg-white/[0.07] hover:text-white/70"
        >
          {icon}
        </button>
      </TooltipTrigger>
      <TooltipContent side="right" className="text-xs">
        {label}
      </TooltipContent>
    </Tooltip>
  );
}

function MobileTabIcon({
  active,
  badge,
  onClick,
  children,
}: {
  active: boolean;
  badge?: number;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "relative flex h-9 w-9 items-center justify-center rounded-lg transition-colors",
        active ? "bg-blue-50 text-blue-600" : "text-slate-400 hover:bg-slate-100 hover:text-slate-600"
      )}
    >
      {children}
      {(badge ?? 0) > 0 && (
        <span className="absolute right-0.5 top-0.5 flex h-3.5 min-w-[14px] items-center justify-center rounded-full bg-red-500 px-0.5 text-[8px] font-bold leading-none text-white">
          {(badge ?? 0) > 99 ? "99+" : badge}
        </span>
      )}
    </button>
  );
}

/* ── List panels ── */

function ChatList() {
  const { state } = useStore();

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <ScrollArea className="min-w-0 flex-1">
        <div className="space-y-px px-2 py-2">
          <SystemConversationItem />
          {state.conversations.map((conv) => (
            <ConversationItem key={conv.conversationId} conv={conv} />
          ))}
        </div>
      </ScrollArea>
    </div>
  );
}

function FriendList({
  onSearchUser,
  onFriendRequests,
}: {
  onSearchUser: () => void;
  onFriendRequests: () => void;
}) {
  const { state, fetchUnhandledApplyCount } = useStore();

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {/* Action bar */}
      <div className="flex gap-2 border-b border-slate-100 px-3 py-2.5">
        <button
          onClick={onSearchUser}
          className="flex h-8 flex-1 items-center gap-1.5 rounded-full bg-slate-100 px-3 text-xs text-slate-500 transition-colors hover:bg-slate-200 hover:text-slate-700"
        >
          <Search className="h-3.5 w-3.5 shrink-0" />
          搜索 / 添加好友
        </button>
        <button
          onClick={() => {
            void fetchUnhandledApplyCount();
            onFriendRequests();
          }}
          className="relative flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-100 text-slate-500 transition-colors hover:bg-slate-200 hover:text-slate-700"
        >
          <Bell className="h-3.5 w-3.5" />
          {state.unhandledApplyCount > 0 && (
            <span className="absolute -right-0.5 -top-0.5 flex h-3.5 min-w-[14px] items-center justify-center rounded-full bg-destructive px-0.5 text-[8px] font-bold text-white">
              {state.unhandledApplyCount > 99 ? "99+" : state.unhandledApplyCount}
            </span>
          )}
        </button>
      </div>

      <ScrollArea className="min-w-0 flex-1">
        {state.friends.length === 0 ? (
          <div className="px-3 pt-2">
            <EmptyState title="暂无好友" description="搜索用户 ID 发起好友申请。" />
          </div>
        ) : (
          <div className="space-y-px px-2 py-2">
            {state.friends.map((friend) => (
              <FriendItem key={friend.friendUserId} friend={friend} />
            ))}
          </div>
        )}
      </ScrollArea>
    </div>
  );
}

function GroupList({
  onSearchGroup,
  onCreateGroup,
  onGroupRequests,
}: {
  onSearchGroup: () => void;
  onCreateGroup: () => void;
  onGroupRequests: () => void;
}) {
  const { state, fetchUnhandledGroupApplyCount } = useStore();

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {/* Action bar */}
      <div className="flex gap-2 border-b border-slate-100 px-3 py-2.5">
        <button
          onClick={onSearchGroup}
          className="flex h-8 flex-1 items-center gap-1.5 rounded-full bg-slate-100 px-3 text-xs text-slate-500 transition-colors hover:bg-slate-200 hover:text-slate-700"
        >
          <Search className="h-3.5 w-3.5 shrink-0" />
          搜索群组
        </button>
        <button
          onClick={onCreateGroup}
          className="flex h-8 items-center gap-1 rounded-full bg-slate-100 px-3 text-xs text-slate-500 transition-colors hover:bg-slate-200 hover:text-slate-700"
        >
          <Plus className="h-3.5 w-3.5" />
          创建
        </button>
        <button
          onClick={() => {
            void fetchUnhandledGroupApplyCount();
            onGroupRequests();
          }}
          className="relative flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-100 text-slate-500 transition-colors hover:bg-slate-200 hover:text-slate-700"
        >
          <Bell className="h-3.5 w-3.5" />
          {state.unhandledGroupApplyCount > 0 && (
            <span className="absolute -right-0.5 -top-0.5 flex h-3.5 min-w-[14px] items-center justify-center rounded-full bg-destructive px-0.5 text-[8px] font-bold text-white">
              {state.unhandledGroupApplyCount > 99 ? "99+" : state.unhandledGroupApplyCount}
            </span>
          )}
        </button>
      </div>

      <ScrollArea className="min-w-0 flex-1">
        {state.myGroups.length === 0 ? (
          <div className="px-3 pt-2">
            <EmptyState title="暂无群组" description="创建或搜索群组加入群聊。" />
          </div>
        ) : (
          <div className="space-y-px px-2 py-2">
            {state.myGroups.map((group) => (
              <GroupItem key={group.groupId} group={group} />
            ))}
          </div>
        )}
      </ScrollArea>
    </div>
  );
}

/* ── List items ── */

function SystemConversationItem() {
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
        isActive
          ? "bg-blue-50 text-blue-950"
          : "hover:bg-slate-50"
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
          {state.systemUnreadCount > 0 && (
            <UnreadBadge count={state.systemUnreadCount} />
          )}
        </div>
      </div>
    </button>
  );
}

function ConversationItem({ conv }: { conv: Conversation }) {
  const { state, dispatch } = useStore();
  const navigate = useNavigate();
  const isActive = state.activeConversationId === conv.conversationId;
  const title = conversationTitle(conv);
  const subtitle =
    formatMessagePreview(conv.latestMsg, conv.conversationType) ||
    (conv.conversationType === ConversationType.GROUP ? `群聊` : "暂无消息");

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

function FriendItem({ friend }: { friend: FriendInfo }) {
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

function GroupItem({ group }: { group: GroupInfo }) {
  const { state, openGroupChat } = useStore();
  const navigate = useNavigate();
  const conversation = useMemo(
    () =>
      state.conversations.find(
        (conv) =>
          conv.conversationType === ConversationType.GROUP &&
          (conv.groupId === group.groupId ||
            conv.conversationId === `group_${group.groupId}`)
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

/* ── Shared micro-component ── */

function UnreadBadge({ count }: { count: number }) {
  return (
    <span className="flex h-5 min-w-[20px] shrink-0 items-center justify-center rounded-full bg-red-500 px-1.5 text-[10px] font-bold leading-none text-white">
      {count > 99 ? "99+" : count}
    </span>
  );
}

/* ── Helpers ── */

function fallbackName(name?: string): string {
  return (displayText(name) || "?").charAt(0).toUpperCase();
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

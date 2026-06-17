import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { SYSTEM_CONVERSATION_ID, useStore, type Conversation, type FriendInfo, type GroupInfo } from "@/store/store";
import { ConversationType, MessageReceiveOption } from "im-sdk";
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
} from "lucide-react";
import UserSearchDialog from "./sidebar/UserSearchDialog";
import GroupSearchDialog from "./sidebar/GroupSearchDialog";
import FriendRequestDialog from "./sidebar/FriendRequestDialog";
import GroupRequestDialog from "./sidebar/GroupRequestDialog";
import { toast } from "sonner";

type Tab = "chats" | "friends" | "groups";

export default function Sidebar() {
  const { state } = useStore();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("chats");
  const [searchUserOpen, setSearchUserOpen] = useState(false);
  const [searchGroupOpen, setSearchGroupOpen] = useState(false);
  const [friendRequestOpen, setFriendRequestOpen] = useState(false);
  const [groupRequestOpen, setGroupRequestOpen] = useState(false);
  const currentUser = state.userId ? state.userProfileCache[state.userId] : undefined;
  const currentDisplayName = displayText(currentUser?.nickname) ?? displayText(state.userId) ?? "未登录";

  return (
    <TooltipProvider>
      <div className="flex h-[42vh] min-h-[260px] w-full shrink-0 flex-col border-b border-slate-200 bg-slate-50/95 md:h-full md:w-80 md:border-b-0 md:border-r">
        <div className="border-b border-slate-200 px-4 py-4">
          <div className="mb-3 flex items-center justify-between gap-2">
            <button
              className="flex min-w-0 flex-1 items-center gap-3 rounded-md px-1 py-1 text-left transition-colors hover:bg-white"
              onClick={() => state.userId && navigate(`/chat/user/${state.userId}`)}
            >
              <Avatar className="h-10 w-10 border border-white shadow-sm">
                <AvatarImage src={currentUser?.faceUrl} />
                <AvatarFallback className="bg-slate-900 text-white">
                  {state.userId ? fallbackName(currentDisplayName) : <User className="h-4 w-4" />}
                </AvatarFallback>
              </Avatar>
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold">
                  {currentDisplayName}
                </div>
                <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
                  <span className={cn("h-1.5 w-1.5 rounded-full", state.connected ? "bg-emerald-500" : "bg-slate-300")} />
                  {state.connected ? "已连接" : "未连接"}
                </div>
              </div>
            </button>
            <div className="flex gap-1">
              <IconAction tip="添加好友" onClick={() => setSearchUserOpen(true)}>
                <UserPlus className="h-4 w-4" />
              </IconAction>
              <IconAction tip="创建群" onClick={() => navigate("/chat/create-group")}>
                <Plus className="h-4 w-4" />
              </IconAction>
            </div>
          </div>

          <div className="grid grid-cols-3 rounded-md border border-slate-200 bg-white p-1 shadow-sm">
            <TabButton active={tab === "chats"} onClick={() => setTab("chats")} label="聊天" count={state.conversations.length + 1}>
              <MessageCircle className="h-3.5 w-3.5" />
            </TabButton>
            <TabButton active={tab === "friends"} onClick={() => setTab("friends")} label="好友" count={state.friends.length}>
              <Contact className="h-3.5 w-3.5" />
            </TabButton>
            <TabButton active={tab === "groups"} onClick={() => setTab("groups")} label="群组" count={state.myGroups.length}>
              <Users className="h-3.5 w-3.5" />
            </TabButton>
          </div>
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
            onCreateGroup={() => navigate("/chat/create-group")}
            onGroupRequests={() => setGroupRequestOpen(true)}
          />
        )}

        <UserSearchDialog open={searchUserOpen} onOpenChange={setSearchUserOpen} />
        <GroupSearchDialog open={searchGroupOpen} onOpenChange={setSearchGroupOpen} />
        <FriendRequestDialog open={friendRequestOpen} onOpenChange={setFriendRequestOpen} />
        <GroupRequestDialog open={groupRequestOpen} onOpenChange={setGroupRequestOpen} />
      </div>
    </TooltipProvider>
  );
}

function IconAction({
  tip,
  onClick,
  children,
}: {
  tip: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          onClick={onClick}
          title={tip}
          aria-label={tip}
          className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-white hover:text-foreground hover:shadow-sm"
        >
          {children}
        </button>
      </TooltipTrigger>
      <TooltipContent side="bottom">{tip}</TooltipContent>
    </Tooltip>
  );
}

function TabButton({
  active,
  onClick,
  label,
  count,
  children,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  count: number;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "flex h-8 items-center justify-center gap-1.5 rounded-md px-2 text-xs font-medium transition-colors",
        active ? "bg-slate-900 text-white shadow-sm" : "text-muted-foreground hover:bg-slate-100 hover:text-foreground"
      )}
    >
      {children}
      <span>{label}</span>
      <span className={cn("text-[10px]", active ? "text-white/70" : "text-muted-foreground")}>{count}</span>
    </button>
  );
}

function ChatList() {
  const { state } = useStore();

  return (
    <ListShell
      title="正在聊天"
      description="最近会话和未读消息"
      empty={state.connected ? "暂无正在聊天的会话" : "未连接到服务器"}
      isEmpty={false}
    >
      <SystemConversationItem />
      {state.conversations.map((conv) => (
        <ConversationItem key={conv.conversationId} conv={conv} />
      ))}
    </ListShell>
  );
}

function SystemConversationItem() {
  const { state, dispatch } = useStore();
  const navigate = useNavigate();
  const isActive = state.activeConversationId === SYSTEM_CONVERSATION_ID;
  const latest = state.latestSystemMessage;
  const subtitle = latest?.summary || latest?.title || "平台通知、业务提醒和账号消息";

  return (
    <button
      onClick={() => {
        dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: SYSTEM_CONVERSATION_ID });
        navigate("/chat");
      }}
      className={cn(
        "flex w-full items-center gap-3 rounded-md px-3 py-3 text-left transition-colors hover:bg-white",
        isActive && "bg-white shadow-sm ring-1 ring-slate-200"
      )}
    >
      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-slate-900 text-white shadow-sm">
        <Bell className="h-5 w-5" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-sm font-medium">系统通知</span>
          {latest?.createdAt && (
            <span className="shrink-0 text-xs text-muted-foreground">{formatTime(latest.createdAt)}</span>
          )}
        </div>
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-xs text-muted-foreground">{subtitle}</span>
          {state.systemUnreadCount > 0 && (
            <span className="flex h-5 min-w-5 shrink-0 items-center justify-center rounded-full bg-primary px-1 text-[10px] text-primary-foreground">
              {state.systemUnreadCount > 99 ? "99+" : state.systemUnreadCount}
            </span>
          )}
        </div>
      </div>
    </button>
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
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex gap-2 border-b border-slate-200 bg-white/70 px-3 py-3">
        <button
          onClick={onSearchUser}
          className="flex h-9 flex-1 items-center rounded-md border bg-background px-2 text-left text-xs text-muted-foreground shadow-sm transition-colors hover:text-foreground"
        >
          <Search className="mr-1.5 h-3.5 w-3.5" /> 添加好友
        </button>
        <button
          onClick={() => {
            void fetchUnhandledApplyCount();
            onFriendRequests();
          }}
          className="relative h-9 rounded-md border bg-background px-3 text-xs text-muted-foreground shadow-sm transition-colors hover:text-foreground"
        >
          申请
          {state.unhandledApplyCount > 0 && (
            <span className="absolute -right-1.5 -top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] text-destructive-foreground">
              {state.unhandledApplyCount > 99 ? "99+" : state.unhandledApplyCount}
            </span>
          )}
        </button>
      </div>

      <ListShell
        title="我的好友"
        description="选择好友开始单聊"
        empty="暂无好友"
        isEmpty={state.friends.length === 0}
      >
        {state.friends.map((friend) => (
          <FriendItem key={friend.friendUserId} friend={friend} />
        ))}
      </ListShell>
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
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex gap-2 border-b border-slate-200 bg-white/70 px-3 py-3">
        <button
          onClick={onSearchGroup}
          className="flex h-9 flex-1 items-center rounded-md border bg-background px-2 text-left text-xs text-muted-foreground shadow-sm transition-colors hover:text-foreground"
        >
          <Search className="mr-1.5 h-3.5 w-3.5" /> 加入群组
        </button>
        <button
          onClick={onCreateGroup}
          className="flex h-9 flex-1 items-center rounded-md border bg-background px-2 text-left text-xs text-muted-foreground shadow-sm transition-colors hover:text-foreground"
        >
          <Plus className="mr-1.5 h-3.5 w-3.5" /> 创建群
        </button>
        <button
          onClick={() => {
            void fetchUnhandledGroupApplyCount();
            onGroupRequests();
          }}
          className="relative h-9 rounded-md border bg-background px-3 text-xs text-muted-foreground shadow-sm transition-colors hover:text-foreground"
        >
          <Bell className="mr-1 inline h-3 w-3" />
          申请
          {state.unhandledGroupApplyCount > 0 && (
            <span className="absolute -right-1.5 -top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] text-destructive-foreground">
              {state.unhandledGroupApplyCount > 99 ? "99+" : state.unhandledGroupApplyCount}
            </span>
          )}
        </button>
      </div>

      <ListShell
        title="我的群组"
        description="选择群组进入群聊"
        empty="暂无群组"
        isEmpty={state.myGroups.length === 0}
      >
        {state.myGroups.map((group) => (
          <GroupItem key={group.groupId} group={group} />
        ))}
      </ListShell>
    </div>
  );
}

function ListShell({
  title,
  description,
  empty,
  isEmpty,
  children,
}: {
  title: string;
  description: string;
  empty: string;
  isEmpty: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="px-4 py-3">
        <div className="text-xs font-semibold uppercase tracking-normal text-slate-500">{title}</div>
        <div className="mt-0.5 text-xs text-muted-foreground">{description}</div>
      </div>
      <ScrollArea className="flex-1">
        {isEmpty ? (
          <div className="mx-4 rounded-md border border-dashed bg-white/70 px-4 py-8 text-center text-sm text-muted-foreground">{empty}</div>
        ) : (
          <div className="space-y-1 px-2 pb-3">{children}</div>
        )}
      </ScrollArea>
    </div>
  );
}

function ConversationItem({ conv }: { conv: Conversation }) {
  const { state, dispatch } = useStore();
  const navigate = useNavigate();
  const isActive = state.activeConversationId === conv.conversationId;
  const title = conversationTitle(conv);
  const subtitle = formatMessagePreview(conv.latestMsg)
    || (conv.conversationType === ConversationType.GROUP ? `[群聊] ${title}` : "暂无消息");

  return (
    <button
      onClick={() => {
        dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: conv.conversationId });
        navigate("/chat");
      }}
      className={cn(
        "flex w-full items-center gap-3 rounded-md px-3 py-3 text-left transition-colors hover:bg-white",
        isActive && "bg-white shadow-sm ring-1 ring-slate-200"
      )}
    >
      <Avatar className="h-10 w-10 border border-white shadow-sm">
        <AvatarImage src={conv.faceUrl} />
        <AvatarFallback>{fallbackName(title)}</AvatarFallback>
      </Avatar>
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-sm font-medium">{title}</span>
          {conv.latestMsgSendTime && (
            <span className="shrink-0 text-xs text-muted-foreground">{formatTime(conv.latestMsgSendTime)}</span>
          )}
        </div>
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-xs text-muted-foreground">{subtitle}</span>
          {conv.unreadCount > 0 && (
            <span className="flex h-5 min-w-5 shrink-0 items-center justify-center rounded-full bg-primary px-1 text-[10px] text-primary-foreground">
              {conv.unreadCount > 99 ? "99+" : conv.unreadCount}
            </span>
          )}
        </div>
      </div>
    </button>
  );
}

function FriendItem({ friend }: { friend: FriendInfo }) {
  const { state, dispatch, removeFriend } = useStore();
  const navigate = useNavigate();
  const displayName = friend.remark || friend.nickname || friend.friendUserId;

  const openChat = () => {
    const existing = state.conversations.find((conv) => conv.conversationType === ConversationType.SINGLE && conv.userId === friend.friendUserId);
    if (existing) {
      dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: existing.conversationId });
      navigate("/chat");
      return;
    }

    const conversation: Conversation = {
      conversationId: singleConversationId(state.userId || "", friend.friendUserId),
      ownerUserId: state.userId || "",
      conversationType: ConversationType.SINGLE,
      userId: friend.friendUserId,
      showName: displayName,
      faceUrl: friend.faceUrl,
      latestMsg: "",
      latestMsgSendTime: 0,
      unreadCount: 0,
      recvMsgOpt: MessageReceiveOption.NORMAL,
      isPinned: false,
    };
    dispatch({ type: "ADD_CONVERSATION", conversation });
    dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: conversation.conversationId });
    navigate("/chat");
  };

  return (
    <div className="group flex items-center justify-between rounded-md px-3 py-2.5 transition-colors hover:bg-white">
      <button onClick={openChat} className="flex min-w-0 flex-1 items-center gap-3 text-left">
        <Avatar className="h-9 w-9 border border-white shadow-sm">
          <AvatarImage src={friend.faceUrl} />
          <AvatarFallback>{fallbackName(displayName)}</AvatarFallback>
        </Avatar>
        <div className="min-w-0">
          <div className="truncate text-sm font-medium">{displayName}</div>
          <div className="truncate text-xs text-muted-foreground">ID: {friend.friendUserId}</div>
        </div>
      </button>

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button className="invisible rounded-md p-1 text-muted-foreground opacity-0 transition-all hover:bg-accent group-hover:visible group-hover:opacity-100">
            <MoreHorizontal className="h-4 w-4" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            className="text-destructive"
            onClick={() => {
              removeFriend(friend.friendUserId);
              toast("已删除好友");
            }}
          >
            <UserMinus className="mr-2 h-4 w-4" />
            删除好友
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

function GroupItem({ group }: { group: GroupInfo }) {
  const { state, dispatch } = useStore();
  const navigate = useNavigate();
  const conversation = useMemo(
    () => state.conversations.find((conv) => conv.conversationType === ConversationType.GROUP && (conv.groupId === group.groupId || conv.conversationId === `group_${group.groupId}`)),
    [group.groupId, state.conversations]
  );

  const openChat = () => {
    if (conversation) {
      dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: conversation.conversationId });
      navigate("/chat");
      return;
    }

    const localConversation: Conversation = {
      conversationId: `group_${group.groupId}`,
      ownerUserId: state.userId || "",
      conversationType: ConversationType.GROUP,
      groupId: group.groupId,
      groupName: groupTitle(group),
      showName: groupTitle(group),
      faceUrl: group.faceUrl,
      latestMsg: "",
      latestMsgSendTime: 0,
      unreadCount: 0,
      recvMsgOpt: MessageReceiveOption.NORMAL,
      isPinned: false,
    };
    dispatch({ type: "ADD_CONVERSATION", conversation: localConversation });
    dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: localConversation.conversationId });
    navigate("/chat");
  };

  return (
    <button onClick={openChat} className="flex w-full items-center gap-3 rounded-md px-3 py-2.5 text-left transition-colors hover:bg-white">
      <Avatar className="h-9 w-9 border border-white shadow-sm">
        <AvatarImage src={group.faceUrl} />
        <AvatarFallback>{fallbackName(groupTitle(group))}</AvatarFallback>
      </Avatar>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium">{groupTitle(group)}</div>
        <div className="truncate text-xs text-muted-foreground">
          ID: {group.groupId}{group.memberCount ? ` · ${group.memberCount} 人` : ""}
        </div>
      </div>
    </button>
  );
}

function fallbackName(name?: string): string {
  return (displayText(name) || "?").charAt(0).toUpperCase();
}

function displayText(value?: string | null): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed || trimmed === "undefined" || trimmed === "null") return undefined;
  return trimmed;
}

function conversationTitle(conv: Conversation): string {
  return displayText(conv.showName)
    ?? displayText(conv.groupName)
    ?? displayText(conv.userId)
    ?? displayText(conv.groupId)
    ?? (conv.conversationType === ConversationType.GROUP ? "未命名群聊" : "未知用户");
}

function groupTitle(group: GroupInfo): string {
  return displayText(group.groupName) ?? displayText(group.groupId) ?? "未命名群聊";
}

function formatMessagePreview(content?: string): string {
  if (!content) return "";
  try {
    const parsed = JSON.parse(content) as { text?: unknown; fileName?: unknown };
    if (typeof parsed.text === "string") return parsed.text;
    if (typeof parsed.fileName === "string") return `[文件] ${parsed.fileName}`;
  } catch {
    return content;
  }
  return content;
}

function singleConversationId(userA: string, userB: string): string {
  return userA <= userB ? `single_${userA}_${userB}` : `single_${userB}_${userA}`;
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const diff = now.getTime() - d.getTime();
  if (diff < 60000) return "刚刚";
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`;
  if (d.getDate() === now.getDate() && d.getMonth() === now.getMonth()) {
    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  }
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

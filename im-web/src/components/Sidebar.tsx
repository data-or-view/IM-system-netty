import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useStore, type Conversation, type FriendInfo, type GroupInfo } from "@/store/store";
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

  return (
    <TooltipProvider>
      <div className="flex h-full w-80 flex-col border-r bg-card">
        <div className="border-b px-3 py-3">
          <div className="mb-3 flex items-center justify-between gap-2">
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold">{state.userId}</div>
              <div className="text-xs text-muted-foreground">
                {state.connected ? "已连接" : "未连接"}
              </div>
            </div>
            <div className="flex gap-1">
              <IconAction tip="添加好友" onClick={() => setSearchUserOpen(true)}>
                <UserPlus className="h-4 w-4" />
              </IconAction>
              <IconAction tip="创建群" onClick={() => navigate("/chat/create-group")}>
                <Plus className="h-4 w-4" />
              </IconAction>
            </div>
          </div>

          <div className="grid grid-cols-3 rounded-lg bg-muted p-1">
            <TabButton active={tab === "chats"} onClick={() => setTab("chats")} label="聊天" count={state.conversations.length}>
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
          className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
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
        "flex items-center justify-center gap-1.5 rounded-md px-2 py-1.5 text-xs font-medium transition-colors",
        active ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
      )}
    >
      {children}
      <span>{label}</span>
      <span className="text-[10px] text-muted-foreground">{count}</span>
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
      isEmpty={state.conversations.length === 0}
    >
      {state.conversations.map((conv) => (
        <ConversationItem key={conv.conversationId} conv={conv} />
      ))}
    </ListShell>
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
      <div className="flex gap-2 border-b px-3 py-2">
        <button
          onClick={onSearchUser}
          className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground transition-colors hover:bg-secondary/80"
        >
          <UserPlus className="mr-1 inline h-3 w-3" /> 添加好友
        </button>
        <button
          onClick={() => {
            void fetchUnhandledApplyCount();
            onFriendRequests();
          }}
          className="relative rounded-md bg-secondary px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-secondary/80"
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
      <div className="flex gap-2 border-b px-3 py-2">
        <button
          onClick={onSearchGroup}
          className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground transition-colors hover:bg-secondary/80"
        >
          <Users className="mr-1 inline h-3 w-3" /> 加入群组
        </button>
        <button
          onClick={onCreateGroup}
          className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground transition-colors hover:bg-secondary/80"
        >
          <Plus className="mr-1 inline h-3 w-3" /> 创建群
        </button>
        <button
          onClick={() => {
            void fetchUnhandledGroupApplyCount();
            onGroupRequests();
          }}
          className="relative rounded-md bg-secondary px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-secondary/80"
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
        <div className="text-sm font-medium">{title}</div>
        <div className="text-xs text-muted-foreground">{description}</div>
      </div>
      <ScrollArea className="flex-1">
        {isEmpty ? (
          <div className="px-4 py-8 text-center text-sm text-muted-foreground">{empty}</div>
        ) : (
          <div className="pb-2">{children}</div>
        )}
      </ScrollArea>
    </div>
  );
}

function ConversationItem({ conv }: { conv: Conversation }) {
  const { state, dispatch } = useStore();
  const isActive = state.activeConversationId === conv.conversationId;
  const subtitle = conv.latestMsg || (conv.conversationType === ConversationType.GROUP ? `[群聊] ${conv.groupName || conv.showName}` : "暂无消息");

  return (
    <button
      onClick={() => dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: conv.conversationId })}
      className={cn(
        "flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-accent/50",
        isActive && "bg-accent"
      )}
    >
      <Avatar className="h-10 w-10">
        <AvatarImage src={conv.faceUrl} />
        <AvatarFallback>{fallbackName(conv.showName)}</AvatarFallback>
      </Avatar>
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-sm font-medium">{conv.showName}</span>
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
    <div className="group flex items-center justify-between px-4 py-2.5 transition-colors hover:bg-accent/50">
      <button onClick={openChat} className="flex min-w-0 flex-1 items-center gap-3 text-left">
        <Avatar className="h-9 w-9">
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
      groupName: group.groupName,
      showName: group.groupName,
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
    <button onClick={openChat} className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-accent/50">
      <Avatar className="h-9 w-9">
        <AvatarImage src={group.faceUrl} />
        <AvatarFallback>{fallbackName(group.groupName)}</AvatarFallback>
      </Avatar>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium">{group.groupName}</div>
        <div className="truncate text-xs text-muted-foreground">
          ID: {group.groupId}{group.memberCount ? ` · ${group.memberCount} 人` : ""}
        </div>
      </div>
    </button>
  );
}

function fallbackName(name?: string): string {
  return (name || "?").charAt(0).toUpperCase();
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

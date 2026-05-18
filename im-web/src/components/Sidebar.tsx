import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useStore, type Conversation } from "@/store/store";
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
} from "lucide-react";
import UserSearchDialog from "./sidebar/UserSearchDialog";
import GroupSearchDialog from "./sidebar/GroupSearchDialog";
import FriendRequestDialog from "./sidebar/FriendRequestDialog";
import { toast } from "sonner";

type Tab = "chats" | "contacts";

export default function Sidebar() {
  const { state } = useStore();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("chats");
  const [searchUserOpen, setSearchUserOpen] = useState(false);
  const [searchGroupOpen, setSearchGroupOpen] = useState(false);
  const [friendRequestOpen, setFriendRequestOpen] = useState(false);

  return (
    <TooltipProvider>
      <div className="flex h-full w-72 flex-col border-r bg-card">
        {/* Header with tabs */}
        <div className="flex items-center justify-between border-b px-3 py-2.5">
          <span className="truncate text-sm font-semibold">{state.userId}</span>
          <div className="flex gap-0.5">
            <TabButton active={tab === "chats"} onClick={() => setTab("chats")} tip="聊天">
              <MessageCircle className="h-4 w-4" />
            </TabButton>
            <TabButton active={tab === "contacts"} onClick={() => setTab("contacts")} tip="通讯录">
              <Users className="h-4 w-4" />
            </TabButton>
          </div>
        </div>

        {/* Tab content */}
        {tab === "chats" && (
          <ChatList
            onSearchUser={() => setSearchUserOpen(true)}
            onSearchGroup={() => setSearchGroupOpen(true)}
            onCreateGroup={() => navigate("/chat/create-group")}
          />
        )}
        {tab === "contacts" && (
          <ContactList onSearchUser={() => setSearchUserOpen(true)} />
        )}

        {/* Dialogs */}
        <UserSearchDialog open={searchUserOpen} onOpenChange={setSearchUserOpen} />
        <GroupSearchDialog open={searchGroupOpen} onOpenChange={setSearchGroupOpen} />
        <FriendRequestDialog open={friendRequestOpen} onOpenChange={setFriendRequestOpen} />
      </div>
    </TooltipProvider>
  );
}

// ====== Tab button ======

function TabButton({
  active,
  onClick,
  tip,
  children,
}: {
  active: boolean;
  onClick: () => void;
  tip: string;
  children: React.ReactNode;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          onClick={onClick}
          className={cn(
            "rounded-md p-1.5 transition-colors",
            active
              ? "bg-accent text-accent-foreground"
              : "text-muted-foreground hover:bg-accent/50"
          )}
        >
          {children}
        </button>
      </TooltipTrigger>
      <TooltipContent side="bottom">{tip}</TooltipContent>
    </Tooltip>
  );
}

// ====== Chat List ======

function ChatList({
  onSearchUser,
  onSearchGroup,
  onCreateGroup,
}: {
  onSearchUser: () => void;
  onSearchGroup: () => void;
  onCreateGroup: () => void;
}) {
  const { state } = useStore();

  return (
    <div className="flex flex-1 flex-col">
      {/* Quick actions */}
      <div className="flex gap-1 border-b px-3 py-2">
        <TooltipProvider>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                onClick={onSearchUser}
                className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-secondary/80"
              >
                <UserPlus className="mr-1 inline h-3 w-3" /> 加好友
              </button>
            </TooltipTrigger>
            <TooltipContent>搜索并添加好友</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                onClick={onSearchGroup}
                className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-secondary/80"
              >
                <Users className="mr-1 inline h-3 w-3" /> 加群
              </button>
            </TooltipTrigger>
            <TooltipContent>搜索并加入群组</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                onClick={onCreateGroup}
                className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-secondary/80"
              >
                <Plus className="mr-1 inline h-3 w-3" /> 创建群
              </button>
            </TooltipTrigger>
            <TooltipContent>创建一个新群</TooltipContent>
          </Tooltip>
        </TooltipProvider>
      </div>

      {!state.connected && (
        <div className="px-4 py-2 text-center text-xs text-muted-foreground">未连接</div>
      )}

      <ScrollArea className="flex-1">
        {state.conversations.length === 0 && state.connected && (
          <div className="p-4 text-center text-sm text-muted-foreground">暂无会话</div>
        )}
        {state.conversations.map((conv) => (
          <ConversationItem key={conv.conversationId} conv={conv} />
        ))}
      </ScrollArea>
    </div>
  );
}

function ConversationItem({ conv }: { conv: Conversation }) {
  const { state, dispatch } = useStore();
  const isActive = state.activeConversationId === conv.conversationId;

  return (
    <button
      onClick={() =>
        dispatch({ type: "SET_ACTIVE_CONVERSATION", conversationId: conv.conversationId })
      }
      className={cn(
        "flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-accent/50",
        isActive && "bg-accent"
      )}
    >
      <Avatar className="h-10 w-10">
        <AvatarImage src={conv.faceUrl} />
        <AvatarFallback>{conv.showName.charAt(0).toUpperCase()}</AvatarFallback>
      </Avatar>
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between">
          <span className="truncate text-sm font-medium">{conv.showName}</span>
          {conv.latestMsgSendTime && (
            <span className="shrink-0 text-xs text-muted-foreground">
              {formatTime(conv.latestMsgSendTime)}
            </span>
          )}
        </div>
        <div className="flex items-center justify-between">
          <span className="truncate text-xs text-muted-foreground">
            {conv.latestMsg || conv.conversationType === 2 ? `[群聊] ${conv.groupName || ""}` : ""}
          </span>
          {conv.unreadCount > 0 && (
            <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary text-[10px] text-primary-foreground">
              {conv.unreadCount > 99 ? "99+" : conv.unreadCount}
            </span>
          )}
        </div>
      </div>
    </button>
  );
}

// ====== Contact List ======

function ContactList({ onSearchUser }: { onSearchUser: () => void }) {
  const { state, removeFriend, fetchUnhandledApplyCount } = useStore();
  const [friendRequestOpen, setFriendRequestOpen] = useState(false);
  const friends = state.friends;

  return (
    <div className="flex flex-1 flex-col">
      <div className="flex items-center gap-1 border-b px-3 py-2">
        <button
          onClick={onSearchUser}
          className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-secondary/80"
        >
          <UserPlus className="mr-1 inline h-3 w-3" /> 添加好友
        </button>
        <button
          onClick={() => {
            setFriendRequestOpen(true);
            fetchUnhandledApplyCount();
          }}
          className="relative rounded-md bg-secondary px-2 py-1.5 text-xs text-muted-foreground hover:bg-secondary/80"
        >
          申请
          {state.unhandledApplyCount > 0 && (
            <span className="absolute -right-1.5 -top-1.5 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-destructive px-1 text-[10px] text-destructive-foreground">
              {state.unhandledApplyCount > 99 ? "99+" : state.unhandledApplyCount}
            </span>
          )}
        </button>
      </div>

      <ScrollArea className="flex-1">
        {friends.length === 0 && (
          <div className="p-4 text-center text-sm text-muted-foreground">暂无好友</div>
        )}

        {friends.map((friend) => (
          <div
            key={friend.friendUserId}
            className="group flex items-center justify-between px-4 py-2.5 transition-colors hover:bg-accent/50"
          >
            <div className="flex items-center gap-3">
              <Avatar className="h-9 w-9">
                <AvatarImage src={friend.faceUrl} />
                <AvatarFallback>
                  {(friend.remark || friend.nickname || friend.friendUserId).charAt(0).toUpperCase()}
                </AvatarFallback>
              </Avatar>
              <div>
                <div className="text-sm font-medium">
                  {friend.remark || friend.nickname || friend.friendUserId}
                </div>
                <div className="text-xs text-muted-foreground">ID: {friend.friendUserId}</div>
              </div>
            </div>

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
        ))}
      </ScrollArea>

      <FriendRequestDialog open={friendRequestOpen} onOpenChange={setFriendRequestOpen} />
    </div>
  );
}

// ====== Time formatter ======

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

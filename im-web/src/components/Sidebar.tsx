import { useStore, type Conversation } from "@/store/store";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { ScrollArea } from "@/components/ui/scroll-area";
import { cn } from "@/lib/utils";
import { MessageCircle, Users, Search } from "lucide-react";
import { useState } from "react";

export default function Sidebar() {
  const { state, dispatch, fetchConversations } = useStore();
  const [tab, setTab] = useState<"chats" | "friends">("chats");

  return (
    <div className="flex h-full w-72 flex-col border-r bg-card">
      {/* Header */}
      <div className="flex items-center justify-between border-b px-4 py-3">
        <h2 className="font-semibold">{state.userId}</h2>
        <div className="flex gap-1">
          <button
            onClick={() => setTab("chats")}
            className={cn(
              "rounded-md p-1.5 transition-colors",
              tab === "chats" ? "bg-accent text-accent-foreground" : "hover:bg-accent/50"
            )}
          >
            <MessageCircle className="h-4 w-4" />
          </button>
          <button
            onClick={() => setTab("friends")}
            className={cn(
              "rounded-md p-1.5 transition-colors",
              tab === "friends" ? "bg-accent text-accent-foreground" : "hover:bg-accent/50"
            )}
          >
            <Users className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Tab Content */}
      {tab === "chats" ? <ChatList /> : <FriendList />}
    </div>
  );
}

function ChatList() {
  const { state, fetchConversations } = useStore();
  const { conversations, activeConversationId, connected } = state;

  return (
    <div className="flex flex-1 flex-col">
      <div className="border-b px-4 py-2">
        <button
          onClick={fetchConversations}
          className="w-full rounded-md bg-secondary px-3 py-1.5 text-left text-sm text-muted-foreground hover:bg-secondary/80"
        >
          <Search className="mr-2 inline h-3.5 w-3.5" />
          搜索或开始新聊天
        </button>
      </div>

      {!connected && (
        <div className="px-4 py-2 text-center text-xs text-muted-foreground">未连接</div>
      )}

      <ScrollArea className="flex-1">
        {conversations.length === 0 && connected && (
          <div className="p-4 text-center text-sm text-muted-foreground">
            暂无会话
          </div>
        )}

        {conversations.map((conv) => (
          <ConversationItem key={conv.conversationId} conv={conv} />
        ))}
      </ScrollArea>
    </div>
  );
}

function ConversationItem({ conv }: { conv: Conversation }) {
  const { state } = useStore();
  const isActive = state.activeConversationId === conv.conversationId;

  return (
    <button
      onClick={() => {
        // Change active conversation
      }}
      className={cn(
        "flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-accent/50",
        isActive && "bg-accent"
      )}
    >
      <Avatar className="h-10 w-10">
        <AvatarImage src={conv.faceUrl} />
        <AvatarFallback>
          {conv.showName.charAt(0).toUpperCase()}
        </AvatarFallback>
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
            {conv.latestMsg || ""}
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

function FriendList() {
  const { state, fetchFriends } = useStore();

  return (
    <ScrollArea className="flex-1">
      <div className="border-b px-4 py-2">
        <button
          onClick={fetchFriends}
          className="w-full rounded-md bg-secondary px-3 py-1.5 text-left text-sm text-muted-foreground hover:bg-secondary/80"
        >
          刷新好友列表
        </button>
      </div>

      {state.friends.length === 0 && (
        <div className="p-4 text-center text-sm text-muted-foreground">
          暂无好友
        </div>
      )}

      {state.friends.map((friend) => (
        <div
          key={friend.friendUserId}
          className="flex items-center gap-3 px-4 py-3 transition-colors hover:bg-accent/50"
        >
          <Avatar className="h-10 w-10">
            <AvatarImage src={friend.faceUrl} />
            <AvatarFallback>
              {(friend.remark || friend.nickname || friend.friendUserId).charAt(0).toUpperCase()}
            </AvatarFallback>
          </Avatar>
          <div className="min-w-0 flex-1">
            <span className="text-sm font-medium">
              {friend.remark || friend.nickname || friend.friendUserId}
            </span>
          </div>
        </div>
      ))}
    </ScrollArea>
  );
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const diff = now.getTime() - d.getTime();

  if (diff < 60000) return "刚刚";
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`;
  if (d.getDate() === now.getDate()) {
    return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
  }
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

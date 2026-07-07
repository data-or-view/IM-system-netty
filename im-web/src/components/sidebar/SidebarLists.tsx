import { Bell, Plus, Search } from "lucide-react";
import { ScrollArea } from "@/components/ui/scroll-area";
import { EmptyState } from "@/components/design-system";
import { useStore } from "@/store/store";
import {
  ConversationItem,
  FriendItem,
  GroupItem,
  SystemConversationItem,
} from "@/components/sidebar/SidebarItems";

export function ChatList() {
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

export function FriendList({
  onSearchUser,
  onFriendRequests,
}: {
  onSearchUser: () => void;
  onFriendRequests: () => void;
}) {
  const { state, fetchUnhandledApplyCount } = useStore();

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
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

export function GroupList({
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

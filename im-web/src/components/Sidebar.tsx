import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Contact, LogOut, MessageCircle, Plus, User, UserPlus, Users } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { StatusDot } from "@/components/design-system";
import { useStore } from "@/store/store";
import UserSearchDialog from "@/components/sidebar/UserSearchDialog";
import GroupSearchDialog from "@/components/sidebar/GroupSearchDialog";
import FriendRequestDialog from "@/components/sidebar/FriendRequestDialog";
import GroupRequestDialog from "@/components/sidebar/GroupRequestDialog";
import { ChatList, FriendList, GroupList } from "@/components/sidebar/SidebarLists";
import { MobileTabIcon, RailAction, RailTab } from "@/components/sidebar/SidebarRail";
import { fallbackName } from "@/components/sidebar/SidebarItems";
import { APP_ROUTES } from "@/config/routes";
import { displayText } from "@/lib/display-formatters";
import { cn } from "@/lib/utils";

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
    state.conversations.reduce((sum, conversation) => sum + (conversation.unreadCount || 0), 0) +
    state.systemUnreadCount;

  return (
    <TooltipProvider>
      <div className="flex h-[44vh] min-h-[280px] w-full shrink-0 flex-col border-b border-slate-200/80 md:h-full md:w-[18.5rem] md:flex-row md:border-b-0 md:border-r">
        <div className="hidden md:flex md:w-16 md:min-w-16 md:max-w-16 md:shrink-0 md:grow-0 md:flex-col md:items-center md:bg-[#1a1c2a] md:pb-5 md:pt-5">
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
            <MobileTabIcon active={tab === "chats"} badge={chatUnread} onClick={() => setTab("chats")}>
              <MessageCircle className="h-5 w-5" />
            </MobileTabIcon>
            <MobileTabIcon active={tab === "friends"} badge={state.unhandledApplyCount} onClick={() => setTab("friends")}>
              <Contact className="h-5 w-5" />
            </MobileTabIcon>
            <MobileTabIcon active={tab === "groups"} badge={state.unhandledGroupApplyCount} onClick={() => setTab("groups")}>
              <Users className="h-5 w-5" />
            </MobileTabIcon>
          </div>
        </div>

        <div className="flex min-h-0 min-w-0 flex-1 flex-col bg-white">
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

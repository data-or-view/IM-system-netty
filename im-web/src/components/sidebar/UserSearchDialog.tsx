import { useState, useCallback } from "react";
import { useStore, type UserInfo } from "@/store/store";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Inbox, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { DialogBody, EmptyState, ResultRow, SearchBar } from "./DialogParts";
import { SEARCH_LOADING_DELAY_MS } from "@/config/ui-timing";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function UserSearchDialog({ open, onOpenChange }: Props) {
  const { state, searchUser, applyFriend } = useStore();
  const [keyword, setKeyword] = useState("");
  const [searching, setSearching] = useState(false);

  const handleSearch = useCallback(() => {
    if (!keyword.trim()) return;
    setSearching(true);
    searchUser(keyword.trim());
    setTimeout(() => setSearching(false), SEARCH_LOADING_DELAY_MS);
  }, [keyword, searchUser]);

  const handleApply = useCallback(
    (targetUser: UserInfo) => {
      applyFriend(targetUser.userId);
      toast("已发送好友申请");
    },
    [applyFriend]
  );

  const isSelf = (uid: string) => uid === state.userId;
  const isAlreadyFriend = (uid: string) => state.friends.some((f) => f.friendUserId === uid);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>搜索用户</DialogTitle>
          <DialogDescription>通过用户 ID 或昵称查找联系人，确认后发送好友申请。</DialogDescription>
        </DialogHeader>

        <DialogBody className="space-y-4">
          <SearchBar
            placeholder="输入 userId 或昵称"
            value={keyword}
            onChange={setKeyword}
            onSearch={handleSearch}
            loading={searching}
            disabled={!keyword.trim()}
          />

        <div className="max-h-80 space-y-2 overflow-y-auto pr-1">
          {state.searchUsers.length === 0 && keyword && !searching && (
            <EmptyState
              icon={<Inbox className="h-4 w-4" />}
              title="未找到用户"
              description="换一个更完整的用户 ID 或昵称再试一下。"
            />
          )}

          {state.searchUsers.map((user) => (
            <ResultRow key={user.userId}>
              <div className="flex min-w-0 items-center gap-3">
                <Avatar className="h-10 w-10 border border-white shadow-sm">
                  <AvatarFallback className="bg-slate-100 text-sm font-semibold text-slate-700">
                    {(user.nickname || user.userId).charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold text-slate-900">{user.nickname || user.userId}</div>
                  <div className="truncate text-xs text-slate-500">ID: {user.userId}</div>
                </div>
              </div>

              {isSelf(user.userId) ? (
                <span className="shrink-0 rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-500">自己</span>
              ) : isAlreadyFriend(user.userId) ? (
                <span className="shrink-0 rounded-full bg-emerald-50 px-2 py-1 text-xs text-emerald-700">已是好友</span>
              ) : (
                <Button variant="outline" size="sm" onClick={() => handleApply(user)}>
                  <UserPlus className="h-3.5 w-3.5" />
                  加好友
                </Button>
              )}
            </ResultRow>
          ))}
        </div>
        </DialogBody>
      </DialogContent>
    </Dialog>
  );
}

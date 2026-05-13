import { useState, useCallback } from "react";
import { useStore, type UserInfo } from "@/store/store";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Search, UserPlus, Loader2, X } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";

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
    // 搜索完成后设置 searching 为 false
    setTimeout(() => setSearching(false), 500);
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
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>搜索用户</DialogTitle>
        </DialogHeader>

        {/* 搜索输入 */}
        <div className="flex items-center gap-2">
          <Input
            placeholder="输入 userId 或昵称"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
          />
          <Button size="icon" onClick={handleSearch} disabled={!keyword.trim() || searching}>
            {searching ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
          </Button>
        </div>

        {/* 搜索结果 */}
        <div className="max-h-64 space-y-1 overflow-y-auto">
          {state.searchUsers.length === 0 && keyword && !searching && (
            <p className="py-4 text-center text-sm text-muted-foreground">未找到用户</p>
          )}

          {state.searchUsers.map((user) => (
            <div
              key={user.userId}
              className="flex items-center justify-between rounded-lg px-3 py-2 transition-colors hover:bg-accent"
            >
              <div className="flex items-center gap-3">
                <Avatar className="h-9 w-9">
                  <AvatarFallback className="text-xs">
                    {(user.nickname || user.userId).charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div>
                  <div className="text-sm font-medium">{user.nickname || user.userId}</div>
                  <div className="text-xs text-muted-foreground">ID: {user.userId}</div>
                </div>
              </div>

              {isSelf(user.userId) ? (
                <span className="text-xs text-muted-foreground">自己</span>
              ) : isAlreadyFriend(user.userId) ? (
                <span className="text-xs text-muted-foreground">已是好友</span>
              ) : (
                <Button variant="ghost" size="sm" onClick={() => handleApply(user)}>
                  <UserPlus className="mr-1 h-3.5 w-3.5" />
                  加好友
                </Button>
              )}
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}

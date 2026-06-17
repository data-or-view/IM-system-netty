import { useState, useCallback } from "react";
import { useStore, type UserInfo } from "@/store/store";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Inbox, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { DialogBody, EmptyState, ResultRow, SearchBar } from "./DialogParts";
import { getErrorText } from "im-sdk";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function UserSearchDialog({ open, onOpenChange }: Props) {
  const { state, dispatch, searchUser, applyFriend } = useStore();
  const [keyword, setKeyword] = useState("");
  const [searching, setSearching] = useState(false);
  const [applying, setApplying] = useState<Record<string, boolean>>({});
  const [applied, setApplied] = useState<Record<string, boolean>>({});

  const handleSearch = useCallback(() => {
    if (!keyword.trim()) return;
    setSearching(true);
    void searchUser(keyword.trim())
      .catch((err) => {
        console.error("search user failed:", err);
        toast(`搜索失败：${getErrorText(err)}`);
      })
      .finally(() => setSearching(false));
  }, [keyword, searchUser]);

  const handleApply = useCallback(
    async (targetUser: UserInfo) => {
      setApplying((prev) => ({ ...prev, [targetUser.userId]: true }));
      try {
        await applyFriend(targetUser.userId);
        setApplied((prev) => ({ ...prev, [targetUser.userId]: true }));
        toast("已发送好友申请");
      } catch (err) {
        toast(`发送失败：${getErrorText(err)}`);
      } finally {
        setApplying((prev) => ({ ...prev, [targetUser.userId]: false }));
      }
    },
    [applyFriend]
  );

  const isSelf = (uid: string) => uid === state.userId;
  const isAlreadyFriend = (uid: string) => state.friends.some((f) => f.friendUserId === uid);

  const handleOpenChange = useCallback((nextOpen: boolean) => {
    if (!nextOpen) {
      setKeyword("");
      setSearching(false);
      setApplying({});
      setApplied({});
      dispatch({ type: "SET_SEARCH_USERS", list: [] });
    }
    onOpenChange(nextOpen);
  }, [dispatch, onOpenChange]);

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
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
              ) : applied[user.userId] ? (
                <span className="shrink-0 rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-500">已申请</span>
              ) : (
                <Button variant="outline" size="sm" onClick={() => void handleApply(user)} disabled={!!applying[user.userId]}>
                  <UserPlus className="h-3.5 w-3.5" />
                  {applying[user.userId] ? "发送中" : "加好友"}
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

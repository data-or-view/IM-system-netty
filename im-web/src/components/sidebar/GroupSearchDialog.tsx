import { useState, useCallback } from "react";
import { useStore, type GroupInfo } from "@/store/store";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Inbox, Users } from "lucide-react";
import { toast } from "sonner";
import { DialogBody, EmptyState, ResultRow, SearchBar } from "./DialogParts";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function GroupSearchDialog({ open, onOpenChange }: Props) {
  const { state, searchGroup, joinGroup } = useStore();
  const [keyword, setKeyword] = useState("");
  const [searching, setSearching] = useState(false);

  const handleSearch = useCallback(() => {
    if (!keyword.trim()) return;
    setSearching(true);
    searchGroup(keyword.trim());
    setTimeout(() => setSearching(false), 500);
  }, [keyword, searchGroup]);

  const handleJoin = useCallback(
    (g: GroupInfo) => {
      joinGroup(g.groupId);
      toast(`已发送加群申请: ${g.groupName}`);
    },
    [joinGroup]
  );

  const isMember = (gid: string) =>
    state.conversations.some((c) => c.groupId === gid || c.conversationId === gid);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>搜索群组</DialogTitle>
          <DialogDescription>查找公开群或可申请加入的群组。</DialogDescription>
        </DialogHeader>

        <DialogBody className="space-y-4">
          <SearchBar
            placeholder="输入群名关键词"
            value={keyword}
            onChange={setKeyword}
            onSearch={handleSearch}
            loading={searching}
            disabled={!keyword.trim()}
          />

        <div className="max-h-80 space-y-2 overflow-y-auto pr-1">
          {state.searchGroups.length === 0 && keyword && !searching && (
            <EmptyState
              icon={<Inbox className="h-4 w-4" />}
              title="未找到群组"
              description="可以换一个群名关键词，或先创建一个新群。"
            />
          )}

          {state.searchGroups.map((g) => (
            <ResultRow key={g.groupId}>
              <div className="flex min-w-0 items-center gap-3">
                <Avatar className="h-10 w-10 border border-white shadow-sm">
                  <AvatarFallback className="bg-slate-900 text-sm font-semibold text-white">
                    {g.groupName.charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold text-slate-900">{g.groupName}</div>
                  <div className="truncate text-xs text-slate-500">{g.memberCount || "?"} 人 · ID: {g.groupId}</div>
                </div>
              </div>

              {isMember(g.groupId) ? (
                <span className="shrink-0 rounded-full bg-emerald-50 px-2 py-1 text-xs text-emerald-700">已加入</span>
              ) : (
                <Button variant="outline" size="sm" onClick={() => handleJoin(g)}>
                  <Users className="h-3.5 w-3.5" />
                  加入
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

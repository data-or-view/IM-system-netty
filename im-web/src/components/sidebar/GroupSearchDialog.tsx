import { useState, useCallback } from "react";
import { useStore, type GroupInfo } from "@/store/store";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Search, Users, Loader2 } from "lucide-react";
import { toast } from "sonner";

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
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>搜索群组</DialogTitle>
        </DialogHeader>

        <div className="flex items-center gap-2">
          <Input
            placeholder="输入群名关键词"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
          />
          <Button size="icon" onClick={handleSearch} disabled={!keyword.trim() || searching}>
            {searching ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
          </Button>
        </div>

        <div className="max-h-64 space-y-1 overflow-y-auto">
          {state.searchGroups.length === 0 && keyword && !searching && (
            <p className="py-4 text-center text-sm text-muted-foreground">未找到群组</p>
          )}

          {state.searchGroups.map((g) => (
            <div
              key={g.groupId}
              className="flex items-center justify-between rounded-lg px-3 py-2 transition-colors hover:bg-accent"
            >
              <div className="flex items-center gap-3">
                <Avatar className="h-9 w-9">
                  <AvatarFallback className="text-xs">
                    {g.groupName.charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div>
                  <div className="text-sm font-medium">{g.groupName}</div>
                  <div className="text-xs text-muted-foreground">{g.memberCount || "?"} 人</div>
                </div>
              </div>

              {isMember(g.groupId) ? (
                <span className="text-xs text-muted-foreground">已加入</span>
              ) : (
                <Button variant="ghost" size="sm" onClick={() => handleJoin(g)}>
                  <Users className="mr-1 h-3.5 w-3.5" />
                  加入
                </Button>
              )}
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}

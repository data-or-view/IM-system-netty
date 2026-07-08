import { useState, useCallback } from "react";
import { useStore, type GroupInfo } from "@/store/store";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Inbox, Users } from "lucide-react";
import { toast } from "sonner";
import { DialogBody, EmptyState, ResultRow, SearchBar } from "./DialogParts";
import { GroupJoinVerification, getErrorText, type GroupJoinResultValue } from "im-sdk";
import { shortId } from "@/lib/display-formatters";
import { createLogger } from "@/lib/logger";

const log = createLogger("ui.group-search");

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function GroupSearchDialog({ open, onOpenChange }: Props) {
  const { state, dispatch, searchGroup, joinGroup } = useStore();
  const [keyword, setKeyword] = useState("");
  const [searching, setSearching] = useState(false);
  const [joining, setJoining] = useState<Record<string, boolean>>({});
  const [joinResults, setJoinResults] = useState<Record<string, GroupJoinResultValue>>({});

  const handleSearch = useCallback(() => {
    if (!keyword.trim()) return;
    setSearching(true);
    void searchGroup(keyword.trim())
      .catch((err) => {
        log.error("search group failed", { keyword, error: err });
        toast(`搜索失败：${getErrorText(err)}`);
      })
      .finally(() => setSearching(false));
  }, [keyword, searchGroup]);

  const handleJoin = useCallback(
    async (g: GroupInfo) => {
      setJoining((prev) => ({ ...prev, [g.groupId]: true }));
      try {
        const result = await joinGroup(g.groupId);
        setJoinResults((prev) => ({ ...prev, [g.groupId]: result.status }));
        if (result.status === "JOINED" || result.status === "ALREADY_MEMBER") {
          toast(`已加入群聊：${g.groupName}`);
        } else if (result.status === "ALREADY_PENDING") {
          toast("申请已提交，等待管理员审批");
        } else {
          toast(`已发送加群申请：${g.groupName}`);
        }
      } catch (err) {
        toast(`申请失败：${getErrorText(err)}`);
      } finally {
        setJoining((prev) => ({ ...prev, [g.groupId]: false }));
      }
    },
    [joinGroup]
  );

  const isMember = (gid: string) =>
    state.myGroups.some((group) => group.groupId === gid)
    || state.conversations.some((c) => c.groupId === gid || c.conversationId === gid || c.conversationId === `group_${gid}`);

  const handleOpenChange = useCallback((nextOpen: boolean) => {
    if (!nextOpen) {
      setKeyword("");
      setSearching(false);
      setJoining({});
      setJoinResults({});
      dispatch({ type: "SET_SEARCH_GROUPS", list: [] });
    }
    onOpenChange(nextOpen);
  }, [dispatch, onOpenChange]);

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>搜索群组</DialogTitle>
          <DialogDescription>查找公开群或可申请加入的群组。</DialogDescription>
        </DialogHeader>

        <DialogBody className="space-y-4">
          <SearchBar
            placeholder="输入群名或群 ID"
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
              description="可以换一个群名或群 ID，或先创建一个新群。"
            />
          )}

          {state.searchGroups.map((g) => (
            <ResultRow key={g.groupId}>
              <div className="flex min-w-0 items-center gap-3">
                <Avatar className="h-10 w-10 border border-white shadow-sm">
                  <AvatarFallback className="bg-blue-100 text-sm font-semibold text-blue-700">
                    {g.groupName.charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold text-slate-900">{g.groupName}</div>
                  <div className="truncate text-xs text-slate-500">
                    {g.memberCount || "?"} 人 · {joinPolicyText(g.needVerification)} · ID: <span title={g.groupId}>{shortId(g.groupId)}</span>
                  </div>
                </div>
              </div>

              {isMember(g.groupId) ? (
                <span className="shrink-0 rounded-full bg-emerald-50 px-2 py-1 text-xs text-emerald-700">已加入</span>
              ) : joinResults[g.groupId] === "JOINED" || joinResults[g.groupId] === "ALREADY_MEMBER" ? (
                <span className="shrink-0 rounded-full bg-emerald-50 px-2 py-1 text-xs text-emerald-700">已加入</span>
              ) : joinResults[g.groupId] === "APPLY_CREATED" || joinResults[g.groupId] === "ALREADY_PENDING" ? (
                <span className="shrink-0 rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-500">已申请</span>
              ) : g.needVerification === GroupJoinVerification.INVITE_ONLY || g.needVerification === GroupJoinVerification.FORBIDDEN ? (
                <span className="shrink-0 rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-500">
                  {g.needVerification === GroupJoinVerification.INVITE_ONLY ? "仅邀请" : "禁止加入"}
                </span>
              ) : (
                <Button variant="outline" size="sm" onClick={() => void handleJoin(g)} disabled={!!joining[g.groupId]}>
                  <Users className="h-3.5 w-3.5" />
                  {joining[g.groupId] ? "申请中" : "加入"}
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

function joinPolicyText(policy?: GroupInfo["needVerification"]) {
  if (policy === GroupJoinVerification.NEED_APPROVAL) return "需审批";
  if (policy === GroupJoinVerification.INVITE_ONLY) return "仅邀请";
  if (policy === GroupJoinVerification.FORBIDDEN) return "禁止加入";
  return "可直接加入";
}

import { useState, useEffect, useCallback } from "react";
import { useStore } from "@/store/store";
import { FRIEND_APPLY_UPDATED_EVENT } from "@/store/store";
import { im } from "@/sdk/im-sdk";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Check, Inbox, Loader2, X } from "lucide-react";
import { toast } from "sonner";
import { DialogBody, EmptyState, ResultRow } from "./DialogParts";
import { getErrorText } from "im-sdk";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

interface ApplyItem {
  fromUserId: string;
  toUserId: string;
  handleResult: import("im-sdk").ApplyHandleResultValue;
  reqMsg?: string;
  createTime: number;
}

export default function FriendRequestDialog({ open, onOpenChange }: Props) {
  const { state, approveFriend } = useStore();
  const [applies, setApplies] = useState<ApplyItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [processing, setProcessing] = useState<Record<string, boolean>>({});

  const loadApplies = useCallback(async () => {
    setLoading(true);
    try {
      const list = await im.friend.receivedApplyList(true);
      setApplies(list as unknown as ApplyItem[]);
    } catch (err) {
      console.error("load friend applies failed:", err);
      toast(`加载好友申请失败：${getErrorText(err)}`);
    } finally {
      setLoading(false);
    }
  }, [state.userId]);

  useEffect(() => {
    if (open) loadApplies();
  }, [open, loadApplies]);

  useEffect(() => {
    if (!open) return;
    const reload = () => void loadApplies();
    window.addEventListener(FRIEND_APPLY_UPDATED_EVENT, reload);
    return () => window.removeEventListener(FRIEND_APPLY_UPDATED_EVENT, reload);
  }, [open, loadApplies]);

  const handleApprove = async (fromUserId: string, agreed: boolean) => {
    setProcessing((prev) => ({ ...prev, [fromUserId]: true }));
    try {
      await approveFriend(fromUserId, agreed);
      setApplies((prev) => prev.filter((a) => a.fromUserId !== fromUserId));
      toast(agreed ? "已同意好友申请" : "已拒绝好友申请");
    } catch (err) {
      toast(`操作失败：${getErrorText(err)}`);
    } finally {
      setProcessing((prev) => ({ ...prev, [fromUserId]: false }));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>好友申请</DialogTitle>
          <DialogDescription>处理其他用户发来的好友请求。</DialogDescription>
        </DialogHeader>

        <DialogBody>
        {loading ? (
          <div className="flex items-center justify-center rounded-lg border border-slate-200 bg-slate-50 py-10">
            <Loader2 className="h-5 w-5 animate-spin text-slate-500" />
          </div>
        ) : applies.length === 0 ? (
          <EmptyState
            icon={<Inbox className="h-4 w-4" />}
            title="暂无好友申请"
            description="新的好友申请会实时出现在这里。"
          />
        ) : (
          <div className="max-h-80 space-y-2 overflow-y-auto">
            {applies.map((a) => (
              <ResultRow key={a.fromUserId}>
                <div className="flex min-w-0 items-center gap-3">
                  <Avatar className="h-10 w-10 border border-white shadow-sm">
                    <AvatarFallback className="bg-slate-100 text-sm font-semibold text-slate-700">{a.fromUserId.charAt(0).toUpperCase()}</AvatarFallback>
                  </Avatar>
                  <div className="min-w-0">
                    <div className="truncate text-sm font-semibold text-slate-900">{a.fromUserId}</div>
                    {a.reqMsg && (
                      <div className="truncate text-xs text-slate-500">{a.reqMsg}</div>
                    )}
                  </div>
                </div>

                <div className="flex shrink-0 gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    className="text-red-600 hover:border-red-200 hover:bg-red-50 hover:text-red-700"
                    onClick={() => handleApprove(a.fromUserId, false)}
                    disabled={!!processing[a.fromUserId]}
                  >
                    {processing[a.fromUserId] ? (
                      <Loader2 className="h-3 w-3 animate-spin" />
                    ) : (
                      <X className="h-3 w-3" />
                    )}
                  </Button>
                  <Button
                    size="sm"
                    onClick={() => handleApprove(a.fromUserId, true)}
                    disabled={!!processing[a.fromUserId]}
                  >
                    {processing[a.fromUserId] ? (
                      <Loader2 className="h-3 w-3 animate-spin" />
                    ) : (
                      <Check className="h-3 w-3" />
                    )}
                  </Button>
                </div>
              </ResultRow>
            ))}
          </div>
        )}
        </DialogBody>
      </DialogContent>
    </Dialog>
  );
}

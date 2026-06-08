import { useCallback, useEffect, useState } from "react";
import { GROUP_APPLY_UPDATED_EVENT, useStore, type GroupApply } from "@/store/store";
import { im } from "@/sdk/im-sdk";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Check, Inbox, Loader2, Users, X } from "lucide-react";
import { toast } from "sonner";
import { DialogBody, EmptyState, ResultRow } from "./DialogParts";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function GroupRequestDialog({ open, onOpenChange }: Props) {
  const { approveGroupApply, fetchUnhandledGroupApplyCount } = useStore();
  const [applies, setApplies] = useState<GroupApply[]>([]);
  const [loading, setLoading] = useState(false);
  const [processing, setProcessing] = useState<Record<string, boolean>>({});

  const loadApplies = useCallback(async () => {
    setLoading(true);
    try {
      const list = await im.group.applyList(true);
      setApplies(list as unknown as GroupApply[]);
      await fetchUnhandledGroupApplyCount();
    } catch (err) {
      console.error("load group applies failed:", err);
      toast("加载群申请失败");
    } finally {
      setLoading(false);
    }
  }, [fetchUnhandledGroupApplyCount]);

  useEffect(() => {
    if (open) void loadApplies();
  }, [open, loadApplies]);

  useEffect(() => {
    if (!open) return;
    const reload = () => void loadApplies();
    window.addEventListener(GROUP_APPLY_UPDATED_EVENT, reload);
    return () => window.removeEventListener(GROUP_APPLY_UPDATED_EVENT, reload);
  }, [open, loadApplies]);

  const handleApprove = async (apply: GroupApply, agreed: boolean) => {
    const key = applyKey(apply);
    setProcessing((prev) => ({ ...prev, [key]: true }));
    try {
      await approveGroupApply(apply.groupId, apply.userId, agreed);
      setApplies((prev) => prev.filter((item) => applyKey(item) !== key));
      toast(agreed ? "已同意加群申请" : "已拒绝加群申请");
    } catch {
      toast("操作失败");
    } finally {
      setProcessing((prev) => ({ ...prev, [key]: false }));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>群申请</DialogTitle>
          <DialogDescription>审批用户加入群组的请求。</DialogDescription>
        </DialogHeader>

        <DialogBody>
        {loading ? (
          <div className="flex items-center justify-center rounded-lg border border-slate-200 bg-slate-50 py-10">
            <Loader2 className="h-5 w-5 animate-spin text-slate-500" />
          </div>
        ) : applies.length === 0 ? (
          <EmptyState
            icon={<Inbox className="h-4 w-4" />}
            title="暂无加群申请"
            description="需要你处理的加群申请会显示在这里。"
          />
        ) : (
          <div className="max-h-96 space-y-2 overflow-y-auto">
            {applies.map((apply) => {
              const key = applyKey(apply);
              return (
                <ResultRow key={key}>
                  <div className="flex min-w-0 items-center gap-3">
                    <Avatar className="h-10 w-10 border border-white shadow-sm">
                      <AvatarFallback className="bg-slate-900 text-white">
                        <Users className="h-4 w-4" />
                      </AvatarFallback>
                    </Avatar>
                    <div className="min-w-0">
                      <div className="truncate text-sm font-semibold text-slate-900">{apply.userId}</div>
                      <div className="truncate text-xs text-slate-500">申请加入群：{apply.groupId}</div>
                      {apply.reqMsg && (
                        <div className="mt-0.5 truncate text-xs text-slate-500">{apply.reqMsg}</div>
                      )}
                    </div>
                  </div>

                  <div className="ml-3 flex shrink-0 gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      className="text-red-600 hover:border-red-200 hover:bg-red-50 hover:text-red-700"
                      onClick={() => void handleApprove(apply, false)}
                      disabled={!!processing[key]}
                    >
                      {processing[key] ? <Loader2 className="h-3 w-3 animate-spin" /> : <X className="h-3 w-3" />}
                    </Button>
                    <Button size="sm" onClick={() => void handleApprove(apply, true)} disabled={!!processing[key]}>
                      {processing[key] ? <Loader2 className="h-3 w-3 animate-spin" /> : <Check className="h-3 w-3" />}
                    </Button>
                  </div>
                </ResultRow>
              );
            })}
          </div>
        )}
        </DialogBody>
      </DialogContent>
    </Dialog>
  );
}

function applyKey(apply: GroupApply) {
  return `${apply.groupId}:${apply.userId}`;
}

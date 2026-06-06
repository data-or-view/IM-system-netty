import { useCallback, useEffect, useState } from "react";
import { GROUP_APPLY_UPDATED_EVENT, useStore, type GroupApply } from "@/store/store";
import { im } from "@/sdk/im-sdk";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Check, Loader2, Users, X } from "lucide-react";
import { toast } from "sonner";

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
        </DialogHeader>

        {loading ? (
          <div className="flex justify-center py-8">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : applies.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">暂无加群申请</p>
        ) : (
          <div className="max-h-96 space-y-2 overflow-y-auto">
            {applies.map((apply) => {
              const key = applyKey(apply);
              return (
                <div key={key} className="flex items-center justify-between rounded-lg border px-4 py-3">
                  <div className="flex min-w-0 items-center gap-3">
                    <Avatar className="h-10 w-10">
                      <AvatarFallback>
                        <Users className="h-4 w-4" />
                      </AvatarFallback>
                    </Avatar>
                    <div className="min-w-0">
                      <div className="truncate text-sm font-medium">{apply.userId}</div>
                      <div className="truncate text-xs text-muted-foreground">申请加入群：{apply.groupId}</div>
                      {apply.reqMsg && (
                        <div className="mt-0.5 truncate text-xs text-muted-foreground">{apply.reqMsg}</div>
                      )}
                    </div>
                  </div>

                  <div className="ml-3 flex shrink-0 gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      className="text-destructive"
                      onClick={() => void handleApprove(apply, false)}
                      disabled={!!processing[key]}
                    >
                      {processing[key] ? <Loader2 className="h-3 w-3 animate-spin" /> : <X className="h-3 w-3" />}
                    </Button>
                    <Button size="sm" onClick={() => void handleApprove(apply, true)} disabled={!!processing[key]}>
                      {processing[key] ? <Loader2 className="h-3 w-3 animate-spin" /> : <Check className="h-3 w-3" />}
                    </Button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function applyKey(apply: GroupApply) {
  return `${apply.groupId}:${apply.userId}`;
}

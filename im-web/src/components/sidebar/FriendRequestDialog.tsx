import { useState, useEffect, useCallback } from "react";
import { useStore } from "@/store/store";
import { im } from "@/sdk/im-sdk";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Check, X, Loader2 } from "lucide-react";
import { toast } from "sonner";

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
    } finally {
      setLoading(false);
    }
  }, [state.userId]);

  useEffect(() => {
    if (open) loadApplies();
  }, [open, loadApplies]);

  const handleApprove = async (fromUserId: string, agreed: boolean) => {
    setProcessing((prev) => ({ ...prev, [fromUserId]: true }));
    try {
      await approveFriend(fromUserId, agreed);
      setApplies((prev) => prev.filter((a) => a.fromUserId !== fromUserId));
      toast(agreed ? "已同意好友申请" : "已拒绝好友申请");
    } catch {
      toast("操作失败");
    } finally {
      setProcessing((prev) => ({ ...prev, [fromUserId]: false }));
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>好友申请</DialogTitle>
        </DialogHeader>

        {loading ? (
          <div className="flex justify-center py-8">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : applies.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">暂无好友申请</p>
        ) : (
          <div className="max-h-80 space-y-2 overflow-y-auto">
            {applies.map((a) => (
              <div
                key={a.fromUserId}
                className="flex items-center justify-between rounded-lg border px-4 py-3"
              >
                <div className="flex items-center gap-3">
                  <Avatar className="h-9 w-9">
                    <AvatarFallback>{a.fromUserId.charAt(0).toUpperCase()}</AvatarFallback>
                  </Avatar>
                  <div>
                    <div className="text-sm font-medium">{a.fromUserId}</div>
                    {a.reqMsg && (
                      <div className="text-xs text-muted-foreground">{a.reqMsg}</div>
                    )}
                  </div>
                </div>

                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    className="text-destructive"
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
              </div>
            ))}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { cn } from "@/lib/utils";

export type ConfirmTone = "default" | "danger" | "warning";

export interface ConfirmDialogState {
  open: boolean;
  title: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  tone?: ConfirmTone;
  loading?: boolean;
  onConfirm?: () => void | Promise<void>;
}

export const emptyConfirmDialog: ConfirmDialogState = {
  open: false,
  title: "",
};

export function ConfirmDialog({
  state,
  onOpenChange,
}: {
  state: ConfirmDialogState;
  onOpenChange: (open: boolean) => void;
}) {
  const tone = state.tone ?? "default";
  return (
    <Dialog open={state.open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md p-0.5">
        <DialogHeader>
          <div className="flex items-start gap-1">
            <div
              className={cn(
                "mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full",
                tone === "danger" && "bg-red-50 text-red-600",
                tone === "warning" && "bg-amber-50 text-amber-600",
                tone === "default" && "bg-blue-50 text-blue-600",
              )}
            >
              <AlertTriangle className="h-3 w-4" />
            </div>
            <div className="min-w-0">
              <DialogTitle>{state.title}</DialogTitle>
              {state.description && (
                <DialogDescription className="mt-1">
                  {state.description}
                </DialogDescription>
              )}
            </div>
          </div>
        </DialogHeader>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={state.loading}
          >
            {state.cancelText || "取消"}
          </Button>
          <Button
            type="button"
            variant={tone === "danger" ? "destructive" : "default"}
            onClick={() => void state.onConfirm?.()}
            disabled={state.loading}
          >
            {state.loading ? "处理中..." : state.confirmText || "确认"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

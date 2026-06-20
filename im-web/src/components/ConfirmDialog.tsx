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
  const confirmButtonClass = cn(
    "h-8 min-w-16 px-3 text-xs font-medium",
    tone === "danger" && "bg-red-600 text-white hover:bg-red-700",
    tone === "warning" &&
      "bg-amber-500 text-white hover:bg-amber-600 focus-visible:ring-amber-500",
  );

  return (
    <Dialog open={state.open} onOpenChange={onOpenChange}>
      <DialogContent className="w-[calc(100vw-2rem)] gap-0 overflow-hidden rounded-xl border border-slate-200/80 bg-white p-0 shadow-xl shadow-slate-950/15 sm:max-w-[22rem]">
        <div className="px-4 pb-3 pt-4">
          <DialogHeader className="space-y-0 text-left">
          <div className="flex items-start gap-3">
            <div
              className={cn(
                "mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full",
                tone === "danger" && "bg-red-50 text-red-600 ring-1 ring-red-100",
                tone === "warning" &&
                  "bg-amber-50 text-amber-600 ring-1 ring-amber-100",
                tone === "default" &&
                  "bg-blue-50 text-blue-600 ring-1 ring-blue-100",
              )}
            >
              <AlertTriangle className="h-3.5 w-3.5" />
            </div>
            <div className="min-w-0">
              <DialogTitle className="text-sm font-semibold leading-5 text-slate-950">
                {state.title}
              </DialogTitle>
              {state.description && (
                <DialogDescription className="mt-1 line-clamp-4 text-xs leading-5 text-slate-500">
                  {state.description}
                </DialogDescription>
              )}
            </div>
          </div>
          </DialogHeader>
        </div>
        <DialogFooter className="flex-row justify-end gap-2 border-t border-slate-100 bg-slate-50/80 px-4 py-3 sm:space-x-0">
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="h-8 min-w-14 border-slate-200 bg-white px-3 text-xs font-medium text-slate-700 hover:bg-slate-100"
            onClick={() => onOpenChange(false)}
            disabled={state.loading}
          >
            {state.cancelText || "取消"}
          </Button>
          <Button
            type="button"
            variant={tone === "danger" ? "destructive" : "default"}
            size="sm"
            className={confirmButtonClass}
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

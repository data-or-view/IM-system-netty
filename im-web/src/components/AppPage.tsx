import { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { ArrowLeft } from "lucide-react";

export function AppPage({
  title,
  description,
  onBack,
  children,
  footer,
}: {
  title: string;
  description?: ReactNode;
  onBack?: () => void;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <div className="flex h-full flex-1 flex-col bg-[var(--app-bg)]">
      <div className="flex items-center gap-3 border-b border-slate-200 bg-white/95 px-5 py-3 shadow-[0_1px_0_rgba(16,24,40,0.03)]">
        {onBack && (
          <Button variant="ghost" size="icon" className="h-9 w-9 shrink-0" onClick={onBack}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
        )}
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-[var(--text-strong)]">{title}</div>
          {description && <div className="truncate text-xs text-[var(--text-muted)]">{description}</div>}
        </div>
      </div>
      <div className="min-h-0 flex-1">{children}</div>
      {footer && <div className="border-t border-slate-200 bg-white/95 px-5 py-3">{footer}</div>}
    </div>
  );
}

export function Surface({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div className={cn("rounded-md border border-slate-200 bg-white shadow-sm shadow-slate-950/[0.03]", className)}>
      {children}
    </div>
  );
}

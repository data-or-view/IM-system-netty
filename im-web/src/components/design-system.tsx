import type { ReactNode } from "react";
import { AlertCircle, CircleDashed, Inbox, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

export function StatusDot({
  tone = "muted",
  pulse = false,
}: {
  tone?: "online" | "offline" | "info" | "warning" | "danger" | "muted";
  pulse?: boolean;
}) {
  return (
    <span
      className={cn(
        "inline-flex h-2 w-2 shrink-0 rounded-full",
        tone === "online" && "bg-[var(--signal-online)]",
        tone === "offline" && "bg-slate-300",
        tone === "info" && "bg-[var(--signal-info)]",
        tone === "warning" && "bg-[var(--signal-warning)]",
        tone === "danger" && "bg-[var(--signal-danger)]",
        tone === "muted" && "bg-slate-400",
        pulse && "animate-pulse",
      )}
    />
  );
}

export function SignalRail({ tone = "info" }: { tone?: "info" | "online" | "warning" | "danger" | "muted" }) {
  return (
    <span className="flex w-5 shrink-0 justify-center self-stretch" aria-hidden="true">
      <span className={cn(
        "mt-1 h-full w-px rounded-full",
        tone === "info" && "bg-[var(--signal-info)]/25",
        tone === "online" && "bg-[var(--signal-online)]/25",
        tone === "warning" && "bg-[var(--signal-warning)]/30",
        tone === "danger" && "bg-[var(--signal-danger)]/30",
        tone === "muted" && "bg-slate-200",
      )} />
    </span>
  );
}

export function EmptyState({
  icon,
  title,
  description,
  className,
}: {
  icon?: ReactNode;
  title: string;
  description: string;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col items-center justify-center rounded-md border border-dashed border-slate-200 bg-white/80 px-5 py-8 text-center", className)}>
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-md border border-slate-200 bg-[var(--surface-subtle)] text-slate-500">
        {icon ?? <Inbox className="h-4 w-4" />}
      </div>
      <div className="text-sm font-semibold text-[var(--text-strong)]">{title}</div>
      <div className="mt-1 max-w-xs text-xs leading-5 text-[var(--text-muted)]">{description}</div>
    </div>
  );
}

export function PageHeader({
  icon,
  title,
  description,
  actions,
}: {
  icon?: ReactNode;
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-slate-200 bg-white/95 px-5 py-3 shadow-[0_1px_0_rgba(16,24,40,0.03)]">
      <div className="flex min-w-0 items-center gap-3">
        {icon && (
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md border border-slate-200 bg-[var(--surface-subtle)] text-[var(--signal-info)]">
            {icon}
          </div>
        )}
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-[var(--text-strong)]">{title}</div>
          {description && <div className="truncate text-xs text-[var(--text-muted)]">{description}</div>}
        </div>
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}

export function MessageStatusIcon({
  status,
  errorText,
}: {
  status: number;
  errorText?: string;
}) {
  if (status === -1) {
    return (
      <span title={errorText || "发送失败"} className="mb-2 text-[var(--signal-danger)]">
        <AlertCircle className="h-4 w-4 fill-red-500/10" />
      </span>
    );
  }
  if (status === 0) {
    return (
      <span title="发送中" className="mb-2 text-slate-400">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
      </span>
    );
  }
  return null;
}

export function StateBadge({
  tone = "muted",
  children,
}: {
  tone?: "online" | "info" | "warning" | "danger" | "muted";
  children: ReactNode;
}) {
  return (
    <span className={cn(
      "inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium",
      tone === "online" && "border-emerald-200 bg-emerald-50 text-emerald-700",
      tone === "info" && "border-blue-200 bg-blue-50 text-blue-700",
      tone === "warning" && "border-amber-200 bg-amber-50 text-amber-700",
      tone === "danger" && "border-red-200 bg-red-50 text-red-700",
      tone === "muted" && "border-slate-200 bg-slate-50 text-slate-600",
    )}>
      {children}
    </span>
  );
}

export function LoadingState({ text = "加载中" }: { text?: string }) {
  return (
    <div className="flex flex-1 items-center justify-center bg-[var(--app-bg)]">
      <div className="flex items-center gap-2 rounded-md border border-slate-200 bg-white px-4 py-3 text-sm text-[var(--text-muted)] shadow-sm">
        <CircleDashed className="h-4 w-4 animate-spin" />
        {text}
      </div>
    </div>
  );
}

import { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { Loader2, Search } from "lucide-react";

export function DialogBody({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn("px-5 pb-5", className)}>{children}</div>;
}

export function SearchBar({
  value,
  placeholder,
  loading,
  disabled,
  onChange,
  onSearch,
}: {
  value: string;
  placeholder: string;
  loading?: boolean;
  disabled?: boolean;
  onChange: (value: string) => void;
  onSearch: () => void;
}) {
  return (
    <div className="flex items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 p-1.5">
      <div className="flex min-w-0 flex-1 items-center gap-2 rounded-md bg-white px-3 shadow-sm ring-1 ring-slate-200 focus-within:ring-2 focus-within:ring-slate-300">
        <Search className="h-4 w-4 shrink-0 text-slate-400" />
        <Input
          className="h-10 border-0 bg-transparent px-0 shadow-none focus-visible:ring-0"
          placeholder={placeholder}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") onSearch();
          }}
        />
      </div>
      <Button
        type="button"
        size="icon"
        className="h-10 w-10 shrink-0"
        onClick={onSearch}
        disabled={disabled || loading}
        title="搜索"
      >
        {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
      </Button>
    </div>
  );
}

export function EmptyState({
  icon,
  title,
  description,
}: {
  icon: ReactNode;
  title: string;
  description: string;
}) {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-200 bg-slate-50 px-5 py-8 text-center">
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-md bg-white text-slate-500 shadow-sm ring-1 ring-slate-200">
        {icon}
      </div>
      <div className="text-sm font-medium text-slate-800">{title}</div>
      <div className="mt-1 max-w-xs text-xs leading-5 text-slate-500">{description}</div>
    </div>
  );
}

export function ResultRow({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div className={cn("flex items-center justify-between gap-3 rounded-lg border border-slate-200 bg-white px-3 py-3 shadow-sm", className)}>
      {children}
    </div>
  );
}

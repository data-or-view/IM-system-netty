import { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { Loader2, Search } from "lucide-react";
import { EmptyState as AppEmptyState } from "@/components/design-system";

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
    <div className="flex items-center gap-2 rounded-md bg-slate-100 p-1.5">
      <div className="flex min-w-0 flex-1 items-center gap-2 rounded-md bg-white px-3 ring-1 ring-slate-200 focus-within:ring-2 focus-within:ring-blue-200">
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
        className="h-10 w-10 shrink-0 bg-blue-600 hover:bg-blue-700"
        onClick={onSearch}
        disabled={disabled || loading}
        aria-label="搜索"
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
    <AppEmptyState icon={icon} title={title} description={description} />
  );
}

export function ResultRow({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div className={cn("flex items-center justify-between gap-3 rounded-md bg-white px-3 py-3 ring-1 ring-slate-200 transition-colors hover:bg-slate-50", className)}>
      {children}
    </div>
  );
}

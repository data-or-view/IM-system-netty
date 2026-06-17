import { Outlet } from "react-router-dom";
import Sidebar from "@/components/Sidebar";
import { Button } from "@/components/ui/button";
import { LogOut } from "lucide-react";
import { useStore } from "@/store/store";

export default function ChatLayout() {
  const { logout } = useStore();
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[var(--app-bg)] p-0 text-[var(--text-strong)] md:p-3">
      <div className="flex h-full w-full flex-col overflow-hidden border border-slate-200 bg-background shadow-sm shadow-slate-950/[0.04] md:flex-row md:rounded-md">
        <Sidebar />
        <main className="min-h-0 min-w-0 flex-1">
          <Outlet />
        </main>
      </div>
      <Button
        variant="ghost"
        size="icon"
        onClick={logout}
        className="absolute bottom-4 right-4 hidden h-9 w-9 rounded-full border bg-background/90 text-muted-foreground shadow-sm backdrop-blur hover:text-foreground md:inline-flex"
        title="退出登录"
      >
        <LogOut className="h-4 w-4" />
      </Button>
    </div>
  );
}

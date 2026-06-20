import { Outlet } from "react-router-dom";
import Sidebar from "@/components/Sidebar";

export default function ChatLayout() {
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[var(--app-bg)] p-0 text-[var(--text-strong)] md:p-3">
      <div className="flex h-full w-full flex-col overflow-hidden border border-slate-200/70 bg-background shadow-lg shadow-slate-950/[0.06] md:flex-row md:rounded-xl">
        <Sidebar />
        <main className="min-h-0 min-w-0 flex-1">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

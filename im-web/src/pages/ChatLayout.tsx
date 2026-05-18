import { Outlet } from "react-router-dom";
import Sidebar from "@/components/Sidebar";
import { Button } from "@/components/ui/button";
import { LogOut } from "lucide-react";
import { useStore } from "@/store/store";

export default function ChatLayout() {
  const { logout } = useStore();
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-background">
      <Sidebar />
      <Outlet />
      <Button
        variant="ghost"
        size="icon"
        onClick={logout}
        className="absolute bottom-4 right-4 h-8 w-8 rounded-full opacity-50 hover:opacity-100"
        title="退出登录"
      >
        <LogOut className="h-4 w-4" />
      </Button>
    </div>
  );
}

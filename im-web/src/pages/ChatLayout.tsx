import Sidebar from "@/components/Sidebar";
import ChatArea from "@/components/ChatArea";
import { Button } from "@/components/ui/button";
import { LogOut } from "lucide-react";
import { useStore } from "@/store/store";

export default function ChatLayout() {
  const { state, logout } = useStore();

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-background">
      {/* 侧边栏 */}
      <Sidebar />

      {/* 聊天区域 */}
      <ChatArea />

      {/* 右下角退出按钮 */}
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

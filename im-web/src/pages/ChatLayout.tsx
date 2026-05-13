import { useEffect } from "react";
import Sidebar from "@/components/Sidebar";
import ChatArea from "@/components/ChatArea";
import { useStore } from "@/store/store";
import { imConnection } from "@/protocol/connection";
import { CMD, type IMHeader } from "@/protocol/protocol";
import { Button } from "@/components/ui/button";
import { LogOut } from "lucide-react";

export default function ChatLayout() {
  const { state, dispatch, logout } = useStore();

  // 监听业务消息
  useEffect(() => {
    const unsubMessage = imConnection.on("message", (frame) => {
      if (!frame) return;
      const header = frame.header;
      const op = parseInt(header._op || "0");

      switch (op) {
        case CMD.LOGIN_ACK:
          // LOGIN_ACK 已在 App.tsx 全局处理
          break;

        case CMD.SINGLE_CHAT: {
          // 收到新消息
          const msg = messageFromHeader(header);
          if (msg) {
            dispatch({ type: "APPEND_MESSAGE", conversationId: msg.conversationId, msg });
          }
          break;
        }

        case CMD.CONVERSATION_GET_ACK: {
          break;
        }

        case CMD.FRIEND_LIST_ACK: {
          break;
        }
      }
    });

    const unsubOpen = imConnection.on("open", () => {
      dispatch({ type: "SET_CONNECTED", connected: true });
      const cachedToken = localStorage.getItem("im_token");
      const cachedUserId = localStorage.getItem("im_userId");
      if (!cachedToken && cachedUserId) {
        imConnection.login(cachedUserId);
      }
    });

    const unsubClose = imConnection.on("close", () => {
      dispatch({ type: "SET_CONNECTED", connected: false });
    });

    return () => {
      unsubMessage();
      unsubOpen();
      unsubClose();
    };
  }, [dispatch]);

  return (
    <div className="flex h-screen flex-col">
      {/* Top Bar */}
      <header className="flex items-center justify-between border-b bg-card px-4 py-2">
        <div className="flex items-center gap-2">
          <div
            className={`h-2 w-2 rounded-full ${state.connected ? "bg-green-500" : "bg-red-500"}`}
          />
          <span className="text-sm font-medium">IM System</span>
          {!state.connected && (
            <span className="text-xs text-muted-foreground">未连接</span>
          )}
        </div>
        <Button variant="ghost" size="icon" onClick={logout}>
          <LogOut className="h-4 w-4" />
        </Button>
      </header>

      {/* Main */}
      <div className="flex flex-1 overflow-hidden">
        <Sidebar />
        <ChatArea />
      </div>
    </div>
  );
}

function messageFromHeader(header: IMHeader) {
  const now = Date.now();
  return {
    messageId: header._mid || `msg_${now}`,
    seq: parseInt(header._seq || "0"),
    senderUserId: header.fromUserId || header.userId || "",
    senderNickname: undefined,
    conversationId: "",
    contentType: parseInt(header.contentType || "1"),
    content: header.content || "",
    createTime: parseInt(header._ts || String(now)),
    status: header.status === "OK" ? 1 : 0,
  };
}

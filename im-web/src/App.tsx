import { useState, useCallback } from "react";
import { StoreProvider, useStore } from "@/store/store";
import { imConnection } from "@/protocol/connection";
import LoginPage from "@/pages/LoginPage";
import ChatLayout from "@/pages/ChatLayout";

function AppContent() {
  const { state, login } = useStore();
  const [connecting, setConnecting] = useState(false);

  const handleLogin = useCallback(
    (userId: string) => {
      if (connecting) return;
      setConnecting(true);

      // 先连接，连接成功后发送登录
      imConnection.connect("localhost", 8081);

      // 监听连接打开，然后登录
      const unsub = imConnection.on("open", () => {
        login(userId);
        setConnecting(false);
        unsub();
      });

      // 5 秒超时
      setTimeout(() => {
        setConnecting(false);
        // 可能已经打开了，没触发 onopen？重试一次
        if (!imConnection.connected) {
          imConnection.connect("localhost", 8081);
          setTimeout(() => {
            if (imConnection.connected) {
              login(userId);
            }
          }, 500);
        }
      }, 5000);
    },
    [login, connecting]
  );

  // 已经登录且有 token → 直接进入聊天页
  if (state.token && state.userId) {
    return <ChatLayout />;
  }

  return <LoginPage onLogin={handleLogin} />;
}

export default function App() {
  return (
    <StoreProvider>
      <AppContent />
    </StoreProvider>
  );
}

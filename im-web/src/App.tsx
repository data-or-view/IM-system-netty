import { useState, useCallback, useRef } from "react";
import { StoreProvider, useStore } from "@/store/store";
import { imConnection } from "@/protocol/connection";
import { CMD } from "@/protocol/protocol";
import LoginPage from "@/pages/LoginPage";
import ChatLayout from "@/pages/ChatLayout";

/** WebSocket 服务器地址 */
function getWsHost(): string {
  if (typeof window !== "undefined") {
    return window.location.hostname;
  }
  return "localhost";
}

function AppContent() {
  const { state, login: storeLogin } = useStore();
  const [connecting, setConnecting] = useState(false);
  const [statusMsg, setStatusMsg] = useState("");
  const connectingRef = useRef(false);

  /** 登录 */
  const handleLogin = useCallback(
    (userId: string, password?: string) => {
      if (connectingRef.current) return;
      connectingRef.current = true;
      setConnecting(true);
      setStatusMsg("连接中...");

      const host = getWsHost();
      imConnection.connect(host, 8081);

      const unsub = imConnection.on("open", () => {
        storeLogin(userId, password);
        setStatusMsg("登录中...");
        unsub();
      });

      if (imConnection.connected) {
        storeLogin(userId, password);
        setStatusMsg("登录中...");
      }

      setTimeout(() => {
        setConnecting(false);
        connectingRef.current = false;
        if (!imConnection.connected) {
          setStatusMsg("连接超时，请检查服务是否运行");
        }
      }, 5000);
    },
    [storeLogin]
  );

  /** 注册 → 成功后自动登录 */
  const handleRegister = useCallback(
    (userId: string, password?: string) => {
      if (connectingRef.current) return;
      connectingRef.current = true;
      setConnecting(true);
      setStatusMsg("注册中...");

      const host = getWsHost();
      imConnection.connect(host, 8081);

      let cleanedUp = false;
      const cleanup = () => {
        if (cleanedUp) return;
        cleanedUp = true;
        connectingRef.current = false;
        setConnecting(false);
      };

      const unsubMsg = imConnection.on("message", (frame) => {
        if (!frame) return;
        const op = parseInt(frame.header._op || "0");
        if (op === CMD.REGISTER_ACK && frame.header.status === "OK") {
          unsubMsg();
          setStatusMsg("注册成功，正在登录...");
          setTimeout(() => {
            storeLogin(userId, password);
            cleanup();
          }, 300);
        } else if (op === CMD.REGISTER_ACK) {
          unsubMsg();
          setStatusMsg(`注册失败: ${frame.header.reason || "未知错误"}`);
          cleanup();
        }
      });

      const unsubOpen = imConnection.on("open", () => {
        unsubOpen();
        imConnection.register(userId, password);
      });

      if (imConnection.connected) {
        imConnection.register(userId, password);
      }

      setTimeout(() => {
        if (!cleanedUp) {
          setStatusMsg("连接超时，请检查服务是否运行");
          cleanup();
        }
      }, 5000);
    },
    [storeLogin]
  );

  if (state.token && state.userId) {
    return <ChatLayout />;
  }

  return (
    <LoginPage
      onLogin={handleLogin}
      onRegister={handleRegister}
      connecting={connecting}
      statusMsg={statusMsg}
    />
  );
}

export default function App() {
  return (
    <StoreProvider>
      <AppContent />
    </StoreProvider>
  );
}

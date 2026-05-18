import { useState, useCallback, useRef } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import { StoreProvider, useStore } from "@/store/store";
import { im } from "@/sdk/im-sdk";
import LoginPage from "@/pages/LoginPage";
import ChatLayout from "@/pages/ChatLayout";
import ChatArea from "@/components/ChatArea";
import CreateGroupPage from "@/pages/CreateGroupPage";
import GroupInfoPage from "@/pages/GroupInfoPage";
import UserProfilePage from "@/pages/UserProfilePage";

function AuthGate() {
  const { state, login: storeLogin } = useStore();
  const [connecting, setConnecting] = useState(false);
  const [statusMsg, setStatusMsg] = useState("");
  const connectingRef = useRef(false);

  const handleLogin = useCallback(
    async (userId: string, password?: string) => {
      if (connectingRef.current) return;
      connectingRef.current = true;
      setConnecting(true);
      setStatusMsg("连接中...");
      im.connect();
      const waitConnected = () =>
        new Promise<void>((resolve) => {
          if (im.state === "connected") return resolve();
          const unsub = im.on("connectionStateChanged", (s) => {
            if (s === "connected") { unsub(); resolve(); }
          });
        });
      const timeoutPromise = new Promise<void>((_, reject) => {
        setTimeout(() => reject(new Error("连接超时")), 5000);
      });
      try {
        await Promise.race([waitConnected(), timeoutPromise]);
        setStatusMsg("登录中...");
        await storeLogin(userId, password);
        setStatusMsg("");
      } catch {
        setStatusMsg("登录失败");
      } finally {
        setConnecting(false);
        connectingRef.current = false;
      }
    },
    [storeLogin]
  );

  const handleRegister = useCallback(
    async (userId: string, password?: string) => {
      if (connectingRef.current) return;
      connectingRef.current = true;
      setConnecting(true);
      setStatusMsg("注册中...");
      im.connect();
      const waitConnected = () =>
        new Promise<void>((resolve) => {
          if (im.state === "connected") return resolve();
          const unsub = im.on("connectionStateChanged", (s) => {
            if (s === "connected") { unsub(); resolve(); }
          });
        });
      const timeoutPromise = new Promise<void>((_, reject) => {
        setTimeout(() => reject(new Error("连接超时")), 5000);
      });
      try {
        await Promise.race([waitConnected(), timeoutPromise]);
        setStatusMsg("注册中...");
        await im.user.register(userId, password);
        setStatusMsg("注册成功，正在登录...");
        await storeLogin(userId, password);
        setStatusMsg("");
      } catch {
        setStatusMsg("注册失败");
      } finally {
        setConnecting(false);
        connectingRef.current = false;
      }
    },
    [storeLogin]
  );

  if (!state.token || !state.userId) {
    return (
      <LoginPage
        onLogin={handleLogin}
        onRegister={handleRegister}
        connecting={connecting}
        statusMsg={statusMsg}
      />
    );
  }

  return (
    <Routes>
      <Route path="/chat" element={<ChatLayout />}>
        <Route index element={<ChatArea />} />
        <Route path="create-group" element={<CreateGroupPage />} />
        <Route path="group/:groupId" element={<GroupInfoPage />} />
        <Route path="user/:userId" element={<UserProfilePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/chat" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <StoreProvider>
      <Routes>
        <Route path="/login" element={<AuthGate />} />
        <Route path="/chat/*" element={<AuthGate />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </StoreProvider>
  );
}

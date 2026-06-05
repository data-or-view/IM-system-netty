import { useState, useCallback, useEffect, useRef } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import { Toaster } from "sonner";
import { StoreProvider, useStore } from "@/store/store";
import { im } from "@/sdk/im-sdk";
import LoginPage from "@/pages/LoginPage";
import ChatLayout from "@/pages/ChatLayout";
import ChatArea from "@/components/ChatArea";
import CreateGroupPage from "@/pages/CreateGroupPage";
import GroupInfoPage from "@/pages/GroupInfoPage";
import UserProfilePage from "@/pages/UserProfilePage";
import { CallProvider } from "@/components/call/CallProvider";
import { CallDialog } from "@/components/call/CallDialog";

function AuthGate() {
  const { state, login: storeLogin, register: storeRegister, logout } = useStore();
  const [connecting, setConnecting] = useState(false);
  const [checkingAuth, setCheckingAuth] = useState(Boolean(state.token && state.userId));
  const [statusMsg, setStatusMsg] = useState("");
  const connectingRef = useRef(false);
  const checkedAuthKeyRef = useRef<string | null>(null);

  useEffect(() => {
    if (!state.token || !state.userId) {
      checkedAuthKeyRef.current = null;
      setCheckingAuth(false);
      return;
    }

    const authKey = `${state.userId}:${state.token}`;
    if (checkedAuthKeyRef.current === authKey) {
      setCheckingAuth(false);
      return;
    }

    let cancelled = false;
    const isInitialCheck = checkedAuthKeyRef.current === null;
    if (isInitialCheck) {
      setCheckingAuth(true);
    }
    im.user.info(state.userId)
      .then(() => {
        if (!cancelled) {
          checkedAuthKeyRef.current = authKey;
          setCheckingAuth(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          checkedAuthKeyRef.current = null;
          logout();
          setCheckingAuth(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [logout, state.token, state.userId]);

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
    async (params: { nickname?: string; password?: string }) => {
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
        const generatedUserId = await storeRegister(params);
        setStatusMsg(`注册成功，你的用户 ID：${generatedUserId}`);
      } catch {
        setStatusMsg("注册失败");
      } finally {
        setConnecting(false);
        connectingRef.current = false;
      }
    },
    [storeRegister]
  );

  if (checkingAuth) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-slate-100">
        <div className="rounded-xl border bg-card px-6 py-5 text-center shadow-lg">
          <div className="text-sm font-medium">正在校验登录状态...</div>
          <div className="mt-1 text-xs text-muted-foreground">如果后端数据已重置，会自动回到登录页</div>
        </div>
      </div>
    );
  }

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
    <CallProvider>
    <Routes>
      <Route path="/chat" element={<ChatLayout />}>
        <Route index element={<ChatArea />} />
        <Route path="create-group" element={<CreateGroupPage />} />
        <Route path="group/:groupId" element={<GroupInfoPage />} />
        <Route path="user/:userId" element={<UserProfilePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/chat" replace />} />
    </Routes>
      <CallDialog />
    </CallProvider>
  );
}

export default function App() {
  return (
    <StoreProvider>
      <Routes>
        <Route path="*" element={<AuthGate />} />
      </Routes>
      <Toaster richColors position="top-center" />
    </StoreProvider>
  );
}

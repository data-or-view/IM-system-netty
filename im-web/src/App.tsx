import { Suspense, lazy, useState, useCallback, useEffect, useRef } from "react";
import { Routes, Route, Navigate, useLocation, useNavigate } from "react-router-dom";
import { Toaster } from "sonner";
import { StoreProvider, useStore } from "@/store/store";
import { im } from "@/sdk/im-sdk";
import LoginPage from "@/pages/LoginPage";
import { CallProvider } from "@/components/call/CallProvider";
import RouteErrorBoundary from "@/components/RouteErrorBoundary";
import GlobalErrorHandler from "@/components/GlobalErrorHandler";
import { LoadingState } from "@/components/design-system";
import { resolveAuthRoute } from "@/config/route-guards";
import { APP_ROUTES } from "@/config/routes";
import { authCheckFailureMessage, isAuthExpiredError, notifyAppError } from "@/lib/app-errors";

const ChatLayout = lazy(() => import("@/pages/ChatLayout"));
const ChatArea = lazy(() => import("@/components/ChatArea"));
const CreateGroupPage = lazy(() => import("@/pages/CreateGroupPage"));
const GroupInfoPage = lazy(() => import("@/pages/GroupInfoPage"));
const UserProfilePage = lazy(() => import("@/pages/UserProfilePage"));
const CallDialog = lazy(() => import("@/components/call/CallDialog").then((mod) => ({ default: mod.CallDialog })));

function PageFallback() {
  return (
    <LoadingState text="正在加载工作台" />
  );
}

function AuthGate() {
  const { state, login: storeLogin, register: storeRegister, logout } = useStore();
  const location = useLocation();
  const navigate = useNavigate();
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
      .catch((err) => {
        if (!cancelled) {
          if (isAuthExpiredError(err)) {
            checkedAuthKeyRef.current = null;
            logout();
          } else {
            checkedAuthKeyRef.current = authKey;
            notifyAppError(err, authCheckFailureMessage(err), "auth-check");
          }
          setCheckingAuth(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [logout, state.token, state.userId]);

  const routeDecision = resolveAuthRoute({
    authenticated: Boolean(state.token && state.userId),
    pathname: location.pathname,
    search: location.search,
    hash: location.hash,
  });
  const redirectTarget = "redirectTarget" in routeDecision ? routeDecision.redirectTarget : APP_ROUTES.chat;

  const handleLogin = useCallback(
    async (userId: string, password?: string) => {
      if (connectingRef.current) return;
      connectingRef.current = true;
      setConnecting(true);
      setStatusMsg("连接中...");
      try {
        await im.ready();
        setStatusMsg("登录中...");
        await storeLogin(userId, password);
        setStatusMsg("");
        navigate(redirectTarget, { replace: true });
      } catch (err) {
        const message = "登录失败";
        setStatusMsg(message);
        notifyAppError(err, message, "login");
      } finally {
        setConnecting(false);
        connectingRef.current = false;
      }
    },
    [navigate, redirectTarget, storeLogin]
  );

  const handleRegister = useCallback(
    async (params: { nickname?: string; password?: string }) => {
      if (connectingRef.current) return;
      connectingRef.current = true;
      setConnecting(true);
      setStatusMsg("注册中...");
      try {
        await im.ready();
        setStatusMsg("注册中...");
        const generatedUserId = await storeRegister(params);
        setStatusMsg(`注册成功，你的用户 ID：${generatedUserId}`);
        navigate(redirectTarget, { replace: true });
      } catch (err) {
        const message = "注册失败";
        setStatusMsg(message);
        notifyAppError(err, message, "register");
      } finally {
        setConnecting(false);
        connectingRef.current = false;
      }
    },
    [navigate, redirectTarget, storeRegister]
  );

  if (checkingAuth) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[var(--app-bg)] px-4">
        <div className="rounded-md border border-slate-200 bg-white px-6 py-5 text-center shadow-sm">
          <div className="text-sm font-medium">正在校验登录状态...</div>
          <div className="mt-1 text-xs text-muted-foreground">网络异常时会保留当前登录状态</div>
        </div>
      </div>
    );
  }

  if (routeDecision.kind === "redirect") {
    return <Navigate to={routeDecision.to} replace />;
  }

  if (routeDecision.kind === "show-login") {
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
      <RouteErrorBoundary onNavigateHome={() => navigate(APP_ROUTES.chat, { replace: true })}>
        <Suspense fallback={<PageFallback />}>
          <Routes>
            <Route path={APP_ROUTES.chat} element={<ChatLayout />}>
              <Route index element={<ChatArea />} />
              <Route path="create-group" element={<CreateGroupPage />} />
              <Route path="group/:groupId" element={<GroupInfoPage />} />
              <Route path="user/:userId" element={<UserProfilePage />} />
            </Route>
            <Route path="*" element={<Navigate to={APP_ROUTES.chat} replace />} />
          </Routes>
          <CallDialog />
        </Suspense>
      </RouteErrorBoundary>
    </CallProvider>
  );
}

export default function App() {
  return (
    <StoreProvider>
      <GlobalErrorHandler />
      <Routes>
        <Route path="*" element={<AuthGate />} />
      </Routes>
      <Toaster richColors position="top-center" />
    </StoreProvider>
  );
}

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

interface LoginPageProps {
  onLogin: (userId: string, password?: string) => void;
  onRegister: (userId: string, password?: string) => void;
  connecting: boolean;
  statusMsg: string;
}

export default function LoginPage({ onLogin, onRegister, connecting, statusMsg }: LoginPageProps) {
  const [userId, setUserId] = useState("");
  const [password, setPassword] = useState("");
  const [mode, setMode] = useState<"login" | "register">("login");

  const isLogin = mode === "login";

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!userId.trim() || connecting) return;
    if (isLogin) {
      onLogin(userId.trim(), password);
    } else {
      onRegister(userId.trim(), password);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-slate-100">
      <div className="w-full max-w-sm rounded-xl border bg-card p-8 shadow-lg">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold">IM System</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {isLogin ? "输入用户 ID 登录" : "注册新用户"}
          </p>
        </div>

        {/* 登录/注册切换 */}
        <div className="mb-4 flex rounded-lg bg-muted p-1">
          <button
            type="button"
            onClick={() => setMode("login")}
            className={cn(
              "flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-all",
              isLogin ? "bg-background text-foreground shadow-sm" : "text-muted-foreground"
            )}
          >
            登录
          </button>
          <button
            type="button"
            onClick={() => setMode("register")}
            className={cn(
              "flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-all",
              !isLogin ? "bg-background text-foreground shadow-sm" : "text-muted-foreground"
            )}
          >
            注册
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <Input
            placeholder="用户 ID"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            disabled={connecting}
          />

          {!isLogin && (
            <Input
              type="password"
              placeholder="密码（可选）"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={connecting}
            />
          )}

          {isLogin && (
            <Input
              type="password"
              placeholder="密码（可选）"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={connecting}
            />
          )}

          {/* 状态信息 */}
          {statusMsg && (
            <p className="text-center text-xs text-muted-foreground">{statusMsg}</p>
          )}

          <Button
            type="submit"
            className="w-full"
            disabled={!userId.trim() || connecting}
          >
            {connecting ? "处理中..." : isLogin ? "登录" : "注册"}
          </Button>
        </form>
      </div>
    </div>
  );
}

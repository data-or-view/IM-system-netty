import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { Activity, Loader2, MessageCircle, RadioTower } from "lucide-react";
import { StatusDot } from "@/components/design-system";

interface LoginPageProps {
  onLogin: (userId: string, password?: string) => void;
  onRegister: (params: { nickname?: string; password?: string }) => void;
  connecting: boolean;
  statusMsg: string;
}

export default function LoginPage({ onLogin, onRegister, connecting, statusMsg }: LoginPageProps) {
  const [userId, setUserId] = useState("");
  const [nickname, setNickname] = useState("");
  const [password, setPassword] = useState("");
  const [mode, setMode] = useState<"login" | "register">("login");

  const isLogin = mode === "login";
  const canSubmit = isLogin ? Boolean(userId.trim()) : Boolean(password.trim());
  const helperText = isLogin
    ? "密码可为空，用于兼容未设置密码的测试账号。"
    : "密码必填；昵称可为空，未填写时使用服务器生成的用户 ID。";

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (connecting) return;
    if (isLogin) {
      if (!userId.trim()) return;
      onLogin(userId.trim(), password);
    } else {
      if (!password.trim()) return;
      onRegister({ nickname: nickname.trim() || undefined, password });
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[var(--app-bg)] px-4">
      <div className="grid w-full max-w-4xl overflow-hidden rounded-md border border-slate-200 bg-white shadow-xl shadow-slate-950/10 md:grid-cols-[0.95fr_1.05fr]">
        <div className="relative overflow-hidden border-b border-slate-200 bg-[var(--brand-ink)] px-8 py-8 text-white md:border-b-0 md:border-r">
          <div className="absolute inset-y-0 right-0 w-px bg-gradient-to-b from-transparent via-white/30 to-transparent" />
          <div className="mb-8 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs text-white/70">
            <StatusDot tone="online" pulse />
            Signal Desk
          </div>
          <div className="mb-5 flex h-12 w-12 items-center justify-center rounded-md border border-white/10 bg-white/10">
            <RadioTower className="h-5 w-5" />
          </div>
          <h1 className="text-2xl font-semibold tracking-tight">IM System</h1>
          <p className="mt-3 max-w-xs text-sm leading-6 text-white/65">
            {isLogin ? "进入实时消息、群组和通话调度台。" : "创建账号后进入消息工作台。"}
          </p>
          <div className="mt-8 space-y-3 text-xs text-white/60">
            <div className="flex items-center gap-2">
              <Activity className="h-3.5 w-3.5 text-emerald-300" />
              WebSocket 在线状态、历史消息和通话信令统一收敛
            </div>
            <div className="flex items-center gap-2">
              <MessageCircle className="h-3.5 w-3.5 text-blue-300" />
              登录后会回到你刚才访问的聊天页面
            </div>
          </div>
        </div>
        <div className="p-7 md:p-8">
        <div className="mb-5">
          <div className="text-sm font-semibold text-[var(--text-strong)]">{isLogin ? "登录工作台" : "注册新账号"}</div>
          <div className="mt-1 text-xs text-[var(--text-muted)]">
            {isLogin ? "使用用户 ID 继续你的会话。" : "设置密码后由服务器生成用户 ID。"}
          </div>
        </div>

        {/* 登录/注册切换 */}
        <div className="mb-5 grid grid-cols-2 rounded-md border border-slate-200 bg-[var(--surface-subtle)] p-1">
          <button
            type="button"
            onClick={() => setMode("login")}
            className={cn(
              "rounded-md px-3 py-2 text-sm font-medium transition-all",
              isLogin ? "bg-white text-[var(--text-strong)] shadow-sm" : "text-slate-500 hover:text-slate-900"
            )}
          >
            登录
          </button>
          <button
            type="button"
            onClick={() => setMode("register")}
            className={cn(
              "rounded-md px-3 py-2 text-sm font-medium transition-all",
              !isLogin ? "bg-white text-[var(--text-strong)] shadow-sm" : "text-slate-500 hover:text-slate-900"
            )}
          >
            注册
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {isLogin ? (
            <Input
              placeholder="用户 ID"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              disabled={connecting}
            />
          ) : (
            <Input
              placeholder="昵称，可选"
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
              disabled={connecting}
            />
          )}

          {!isLogin && (
            <Input
              type="password"
              placeholder="密码，必填"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              disabled={connecting}
            />
          )}

          {isLogin && (
            <Input
              type="password"
              placeholder="密码，可选"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={connecting}
            />
          )}

          <p className="text-xs leading-5 text-slate-500">{helperText}</p>

          {/* 状态信息 */}
          {statusMsg && (
            <p className="rounded-md border border-slate-200 bg-[var(--surface-subtle)] px-3 py-2 text-center text-xs text-[var(--text-muted)]">{statusMsg}</p>
          )}

          <Button
            type="submit"
            className="w-full bg-[var(--brand-ink)] hover:bg-slate-800"
            disabled={!canSubmit || connecting}
          >
            {connecting && <Loader2 className="h-4 w-4 animate-spin" />}
            {connecting ? "处理中..." : isLogin ? "登录" : "注册"}
          </Button>
        </form>
        </div>
      </div>
    </div>
  );
}

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { Loader2, MessageCircle } from "lucide-react";
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
      <div className="grid w-full max-w-4xl overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm md:grid-cols-[0.9fr_1.1fr]">
        <div className="flex flex-col justify-between border-b border-slate-200 bg-slate-50 px-8 py-8 md:border-b-0 md:border-r">
          <div>
            <div className="mb-6 flex h-12 w-12 items-center justify-center rounded-xl bg-blue-600 text-white shadow-sm">
              <MessageCircle className="h-6 w-6" />
            </div>
            <h1 className="text-2xl font-semibold tracking-tight text-slate-950">IM System</h1>
            <p className="mt-3 max-w-xs text-sm leading-6 text-slate-600">
              面向开发和协作场景的实时聊天工具。登录后继续查看会话、好友和群组。
            </p>
          </div>
          <div className="mt-10 space-y-2 rounded-md bg-white p-4 text-xs text-slate-600 ring-1 ring-slate-200">
            <div className="flex items-center gap-2 font-medium text-slate-900">
              <StatusDot tone={connecting ? "warning" : "online"} pulse={connecting} />
              {connecting ? "正在连接服务" : "准备连接"}
            </div>
            <p>登录成功后会自动回到你刚才访问的聊天页面。</p>
          </div>
        </div>

        <div className="p-7 md:p-8">
          <div className="mb-5">
            <div className="text-base font-semibold text-[var(--text-strong)]">{isLogin ? "登录" : "注册账号"}</div>
            <div className="mt-1 text-xs text-[var(--text-muted)]">
              {isLogin ? "使用用户 ID 继续聊天。" : "创建账号后进入聊天工作区。"}
            </div>
          </div>

          <div className="mb-5 grid grid-cols-2 rounded-md bg-slate-100 p-1">
            <button
              type="button"
              onClick={() => setMode("login")}
              className={cn(
                "rounded-md px-3 py-2 text-sm font-medium transition-colors",
                isLogin ? "bg-white text-blue-700 shadow-sm" : "text-slate-500 hover:text-slate-900"
              )}
            >
              登录
            </button>
            <button
              type="button"
              onClick={() => setMode("register")}
              className={cn(
                "rounded-md px-3 py-2 text-sm font-medium transition-colors",
                !isLogin ? "bg-white text-blue-700 shadow-sm" : "text-slate-500 hover:text-slate-900"
              )}
            >
              注册
            </button>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            {isLogin ? (
              <Input placeholder="用户 ID" value={userId} onChange={(e) => setUserId(e.target.value)} disabled={connecting} />
            ) : (
              <Input placeholder="昵称，可选" value={nickname} onChange={(e) => setNickname(e.target.value)} disabled={connecting} />
            )}

            <Input
              type="password"
              placeholder={isLogin ? "密码，可选" : "密码，必填"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required={!isLogin}
              disabled={connecting}
            />

            <p className="text-xs leading-5 text-slate-500">{helperText}</p>

            {statusMsg && (
              <p className="rounded-md bg-slate-50 px-3 py-2 text-center text-xs text-[var(--text-muted)] ring-1 ring-slate-200">{statusMsg}</p>
            )}

            <Button type="submit" className="w-full bg-blue-600 hover:bg-blue-700" disabled={!canSubmit || connecting}>
              {connecting && <Loader2 className="h-4 w-4 animate-spin" />}
              {connecting ? "处理中..." : isLogin ? "登录" : "注册"}
            </Button>
          </form>
        </div>
      </div>
    </div>
  );
}

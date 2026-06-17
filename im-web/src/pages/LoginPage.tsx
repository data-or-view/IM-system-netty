import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { Loader2, MessageCircle } from "lucide-react";

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
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4">
      <div className="w-full max-w-md overflow-hidden rounded-lg border border-slate-200 bg-white shadow-xl shadow-slate-950/10">
        <div className="border-b border-slate-100 bg-slate-950 px-8 py-7 text-white">
          <div className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-white/10 ring-1 ring-white/15">
            <MessageCircle className="h-5 w-5" />
          </div>
          <h1 className="text-xl font-semibold">IM System</h1>
          <p className="mt-2 text-sm leading-6 text-white/65">
            {isLogin ? "输入用户 ID 和密码登录聊天工作台。" : "注册后由服务器生成用户 ID。"}
          </p>
        </div>
        <div className="p-8">
        <div className="mb-6 text-center">
        </div>

        {/* 登录/注册切换 */}
        <div className="mb-5 grid grid-cols-2 rounded-md border border-slate-200 bg-slate-50 p-1">
          <button
            type="button"
            onClick={() => setMode("login")}
            className={cn(
              "rounded-md px-3 py-2 text-sm font-medium transition-all",
              isLogin ? "bg-white text-slate-950 shadow-sm" : "text-slate-500 hover:text-slate-900"
            )}
          >
            登录
          </button>
          <button
            type="button"
            onClick={() => setMode("register")}
            className={cn(
              "rounded-md px-3 py-2 text-sm font-medium transition-all",
              !isLogin ? "bg-white text-slate-950 shadow-sm" : "text-slate-500 hover:text-slate-900"
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
            <p className="rounded-md bg-slate-50 px-3 py-2 text-center text-xs text-slate-500">{statusMsg}</p>
          )}

          <Button
            type="submit"
            className="w-full"
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

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { Loader2, MessageCircle, ShieldCheck, Zap } from "lucide-react";
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
    : "密码必填；昵称可选，未填写时使用服务器生成的用户 ID。";

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
    <div className="flex min-h-screen items-center justify-center bg-slate-900 px-4 py-8">
      <div className="grid w-full max-w-[900px] overflow-hidden rounded-2xl shadow-2xl shadow-slate-950/40 md:grid-cols-[1.05fr_0.95fr]">

        {/* ── Left panel: brand + feature highlights ── */}
        <div className="relative flex flex-col overflow-hidden bg-gradient-to-br from-blue-600 via-blue-700 to-violet-700 p-8 md:p-10">
          {/* Decorative circles */}
          <div className="absolute -right-20 -top-20 h-72 w-72 rounded-full bg-white/[0.05]" />
          <div className="absolute -bottom-28 -left-20 h-80 w-80 rounded-full bg-white/[0.05]" />
          <div className="absolute right-10 bottom-32 h-40 w-40 rounded-full bg-white/[0.04]" />

          <div className="relative z-10 flex flex-1 flex-col">
            {/* Logo */}
            <div className="mb-8 flex h-13 w-13 items-center justify-center rounded-2xl bg-white/15 backdrop-blur-sm" style={{ width: 52, height: 52 }}>
              <MessageCircle className="h-6 w-6 text-white" />
            </div>

            <h1 className="text-3xl font-bold tracking-tight text-white">IM System</h1>
            <p className="mt-3 max-w-xs text-base leading-7 text-blue-100">
              安全、快速、可靠的实时通讯平台，支持单聊、群聊与音视频通话。
            </p>

            {/* Feature chips */}
            <div className="mt-8 flex flex-col gap-3">
              <FeatureChip icon={<Zap className="h-4 w-4" />} text="实时消息推送，延迟极低" />
              <FeatureChip icon={<ShieldCheck className="h-4 w-4" />} text="端到端安全传输，数据可靠" />
              <FeatureChip icon={<MessageCircle className="h-4 w-4" />} text="群聊、单聊、音视频一体化" />
            </div>

            {/* Connection status card */}
            <div className="mt-auto pt-10">
              <div className="rounded-xl border border-white/10 bg-white/10 p-4 backdrop-blur-sm">
                <div className="flex items-center gap-2">
                  <StatusDot tone={connecting ? "warning" : "online"} pulse={connecting} />
                  <span className="text-sm font-semibold text-white">
                    {connecting ? "正在连接服务器…" : "服务就绪"}
                  </span>
                </div>
                <p className="mt-1.5 text-xs leading-5 text-blue-200">
                  登录后将自动跳转到上次访问的会话页面
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* ── Right panel: login / register form ── */}
        <div className="flex flex-col justify-center bg-white px-7 py-9 md:px-9 md:py-10">
          <div className="mb-6">
            <h2 className="text-xl font-bold text-slate-900">
              {isLogin ? "欢迎回来" : "创建账号"}
            </h2>
            <p className="mt-1 text-sm text-slate-500">
              {isLogin ? "使用用户 ID 继续聊天。" : "注册后立即进入聊天工作区。"}
            </p>
          </div>

          {/* Mode toggle */}
          <div className="mb-6 grid grid-cols-2 rounded-xl bg-slate-100 p-1">
            <button
              type="button"
              aria-pressed={isLogin}
              onClick={() => setMode("login")}
              className={cn(
                "rounded-lg py-2 text-sm font-medium transition-all",
                isLogin
                  ? "bg-white text-blue-700 shadow-sm"
                  : "text-slate-500 hover:text-slate-800"
              )}
            >
              登录
            </button>
            <button
              type="button"
              aria-pressed={!isLogin}
              onClick={() => setMode("register")}
              className={cn(
                "rounded-lg py-2 text-sm font-medium transition-all",
                !isLogin
                  ? "bg-white text-blue-700 shadow-sm"
                  : "text-slate-500 hover:text-slate-800"
              )}
            >
              注册
            </button>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            {isLogin ? (
              <div className="space-y-1">
                <label htmlFor="login-user-id" className="text-xs font-medium text-slate-600">用户 ID</label>
                <Input
                  id="login-user-id"
                  placeholder="输入您的用户 ID"
                  value={userId}
                  onChange={(e) => setUserId(e.target.value)}
                  disabled={connecting}
                  className="h-10 rounded-lg border-slate-200 bg-slate-50 focus-visible:ring-blue-300"
                />
              </div>
            ) : (
              <div className="space-y-1">
                <label htmlFor="register-nickname" className="text-xs font-medium text-slate-600">昵称（可选）</label>
                <Input
                  id="register-nickname"
                  placeholder="显示名称，可留空"
                  value={nickname}
                  onChange={(e) => setNickname(e.target.value)}
                  disabled={connecting}
                  className="h-10 rounded-lg border-slate-200 bg-slate-50 focus-visible:ring-blue-300"
                />
              </div>
            )}

            <div className="space-y-1">
              <label htmlFor="auth-password" className="text-xs font-medium text-slate-600">
                密码{!isLogin && <span className="ml-1 text-red-500">*</span>}
              </label>
              <Input
                id="auth-password"
                type="password"
                placeholder={isLogin ? "密码（可选）" : "设置密码"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required={!isLogin}
                disabled={connecting}
                className="h-10 rounded-lg border-slate-200 bg-slate-50 focus-visible:ring-blue-300"
              />
            </div>

            <p className="text-xs leading-5 text-slate-400">{helperText}</p>

            {statusMsg && (
              <div className="rounded-lg bg-slate-50 px-3 py-2 text-center text-xs text-slate-500 ring-1 ring-slate-200">
                {statusMsg}
              </div>
            )}

            <Button
              type="submit"
              className="h-11 w-full rounded-xl bg-blue-600 text-sm font-semibold hover:bg-blue-700 focus-visible:ring-blue-400"
              disabled={!canSubmit || connecting}
            >
              {connecting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              {connecting ? "处理中…" : isLogin ? "登录" : "注册并登录"}
            </Button>
          </form>
        </div>
      </div>
    </div>
  );
}

function FeatureChip({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div className="flex items-center gap-2.5">
      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-white/15 text-white">
        {icon}
      </div>
      <span className="text-sm text-blue-100">{text}</span>
    </div>
  );
}

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

interface LoginPageProps {
  onLogin: (userId: string) => void;
}

export default function LoginPage({ onLogin }: LoginPageProps) {
  const [userId, setUserId] = useState("");
  const [loading, setLoading] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!userId.trim()) return;
    setLoading(true);
    onLogin(userId.trim());
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-slate-100">
      <div className="w-full max-w-sm rounded-xl border bg-card p-8 shadow-lg">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold">IM System</h1>
          <p className="mt-1 text-sm text-muted-foreground">输入用户 ID 登录</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <Input
            placeholder="用户 ID"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            disabled={loading}
          />
          <Button type="submit" className="w-full" disabled={!userId.trim() || loading}>
            {loading ? "连接中..." : "登录"}
          </Button>
        </form>
      </div>
    </div>
  );
}

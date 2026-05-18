# IM Web 前端页面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add page-level components (CreateGroup, GroupInfo, UserProfile) and enhance ChatArea with group messaging, message revoke, and history loading.

**Architecture:** Introduce react-router-dom v6 nested routes. ChatLayout becomes the shell with `<Outlet />`. New pages live at `/chat/create-group`, `/chat/group/:groupId`, `/chat/user/:userId`. Store gains group member/user profile caches.

**Tech Stack:** React 18, react-router-dom 7, Tailwind CSS, shadcn/ui (avatar, button, input, dialog, dropdown-menu, scroll-area, separator, tabs)

---

### Task 1: Router Setup — Wrap App with BrowserRouter + Nested Routes

**Files:**
- Modify: `im-web/src/main.tsx`
- Modify: `im-web/src/App.tsx`
- Modify: `im-web/src/pages/ChatLayout.tsx`

- [ ] **Step 1: Modify main.tsx to wrap with BrowserRouter**

Replace the existing content:

```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./index.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>
);
```

- [ ] **Step 2: Modify App.tsx — auth gate + inner Routes for chat pages**

AppContent stays as the auth gate. When authenticated it renders an **inner `<Routes>`** block so child pages render inside `<ChatLayout>` via `<Outlet />`.

Full content of `im-web/src/App.tsx`:

```tsx
import { useState, useCallback, useRef } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import { StoreProvider } from "@/store/store";
import { im } from "@/sdk/im-sdk";
import LoginPage from "@/pages/LoginPage";
import ChatLayout from "@/pages/ChatLayout";
import ChatArea from "@/components/ChatArea";
import CreateGroupPage from "@/pages/CreateGroupPage";
import GroupInfoPage from "@/pages/GroupInfoPage";
import UserProfilePage from "@/pages/UserProfilePage";

/** Inner component that has auth/login logic */
function AppContent() {
  // We need a local store reference WITHOUT useContext hook name clash with Provider import
  const { useStore } = await import("@/store/store");
  const { state, login: storeLogin } = useStore();
  // ── NOTE: The above dynamic import won't work. See corrected static import below. ──
}
```

Wait — React hooks can't use dynamic imports. The correct approach is a separate component. Let me write it properly:

```tsx
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
      const timeout = setTimeout(() => {
        if (connectingRef.current) {
          setStatusMsg("连接超时，请检查服务是否运行");
          setConnecting(false);
          connectingRef.current = false;
        }
      }, 5000);
      try {
        await waitConnected();
        setStatusMsg("登录中...");
        await storeLogin(userId, password);
        setStatusMsg("");
      } catch {
        setStatusMsg("登录失败");
      } finally {
        clearTimeout(timeout);
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
      const timeout = setTimeout(() => {
        if (connectingRef.current) {
          setStatusMsg("连接超时，请检查服务是否运行");
          setConnecting(false);
          connectingRef.current = false;
        }
      }, 5000);
      try {
        await waitConnected();
        setStatusMsg("注册中...");
        await im.user.register(userId, password);
        setStatusMsg("注册成功，正在登录...");
        await storeLogin(userId, password);
        setStatusMsg("");
      } catch {
        setStatusMsg("注册失败");
      } finally {
        clearTimeout(timeout);
        setConnecting(false);
        connectingRef.current = false;
      }
    },
    [storeLogin]
  );

  // Not authenticated → show login page
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

  // Authenticated → inner Routes (render inside ChatLayout's <Outlet />)
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
```

**Important:** The old `AppContent` is renamed to `AuthGate` since it no longer wraps `AppContent` as a direct child — it's now the route element and conditionally shows login or chat routes.

- [ ] **Step 3: Modify ChatLayout.tsx to use `<Outlet />` for nested routing**

Replace `ChatLayout.tsx`:

```tsx
import { Outlet } from "react-router-dom";
import Sidebar from "@/components/Sidebar";
import { Button } from "@/components/ui/button";
import { LogOut } from "lucide-react";
import { useStore } from "@/store/store";

export default function ChatLayout() {
  const { logout } = useStore();
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-background">
      <Sidebar />
      <Outlet />
      <Button
        variant="ghost"
        size="icon"
        onClick={logout}
        className="absolute bottom-4 right-4 h-8 w-8 rounded-full opacity-50 hover:opacity-100"
        title="退出登录"
      >
        <LogOut className="h-4 w-4" />
      </Button>
    </div>
  );
}
```

- [ ] **Step 4: Verify build**

Run: `cd im-web && npx tsc --noEmit`
Expected: No type errors

### Task 2: Store — Add Group Members and User Profile Caches

**Files:**
- Modify: `im-web/src/store/store.tsx`

- [ ] **Step 1: Add GroupMember type and new state fields**

Add to the imports section:
```tsx
export interface GroupMember {
  groupId: string;
  userId: string;
  nickname?: string;
  faceUrl?: string;
  roleLevel: number;
  joinTime: number;
}
```

Add to the `State` interface:
```tsx
  groupMembers: Record<string, GroupMember[]>;
  groupInfoCache: Record<string, GroupInfo>;
  userProfileCache: Record<string, UserInfo>;
```

Add to `initialState`:
```tsx
  groupMembers: {},
  groupInfoCache: {},
  userProfileCache: {},
```

- [ ] **Step 2: Add new Action types**

Add to the `Action` type union:
```tsx
  | { type: "SET_GROUP_MEMBERS"; groupId: string; members: GroupMember[] }
  | { type: "SET_GROUP_INFO"; groupId: string; info: GroupInfo }
  | { type: "SET_USER_PROFILE"; userId: string; info: UserInfo }
```

- [ ] **Step 3: Add reducer cases**

Add before the `default` case:
```tsx
    case "SET_GROUP_MEMBERS":
      return { ...state, groupMembers: { ...state.groupMembers, [action.groupId]: action.members } };
    case "SET_GROUP_INFO":
      return { ...state, groupInfoCache: { ...state.groupInfoCache, [action.groupId]: action.info } };
    case "SET_USER_PROFILE":
      return { ...state, userProfileCache: { ...state.userProfileCache, [action.userId]: action.info } };
```

- [ ] **Step 4: Add new methods to StoreContextType**

Add to the `StoreContextType` interface:
```tsx
  fetchGroupMembers: (groupId: string) => Promise<void>;
  fetchGroupInfo: (groupId: string) => Promise<void>;
  fetchUserProfile: (userId: string) => Promise<void>;
```

- [ ] **Step 5: Add method implementations in StoreProvider**

Add before the Provider return, after existing methods:
```tsx
  const fetchGroupMembers = useCallback(async (groupId: string) => {
    try {
      const members = await im.group.members(groupId);
      dispatch({ type: "SET_GROUP_MEMBERS", groupId, members: members as unknown as GroupMember[] });
    } catch (err) {
      console.error("fetchGroupMembers failed:", err);
    }
  }, []);

  const fetchGroupInfo = useCallback(async (groupId: string) => {
    try {
      const info = await im.group.info(groupId);
      dispatch({ type: "SET_GROUP_INFO", groupId, info: info as unknown as GroupInfo });
    } catch (err) {
      console.error("fetchGroupInfo failed:", err);
    }
  }, []);

  const fetchUserProfile = useCallback(async (userId: string) => {
    try {
      const info = await im.user.info(userId);
      dispatch({ type: "SET_USER_PROFILE", userId, info: info as unknown as UserInfo });
    } catch (err) {
      console.error("fetchUserProfile failed:", err);
    }
  }, []);
```

- [ ] **Step 6: Add new methods to Provider value**

Add to the context value object:
```tsx
        fetchGroupMembers,
        fetchGroupInfo,
        fetchUserProfile,
```

- [ ] **Step 7: Verify build**

Run: `cd im-web && npx tsc --noEmit`
Expected: No type errors

### Task 3: CreateGroupPage

**Files:**
- Create: `im-web/src/pages/CreateGroupPage.tsx`

- [ ] **Step 1: Create the page component**

```tsx
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import { ArrowLeft, Loader2, Check, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { im } from "@/sdk/im-sdk";

export default function CreateGroupPage() {
  const navigate = useNavigate();
  const { state } = useStore();
  const [groupName, setGroupName] = useState("");
  const [faceUrl, setFaceUrl] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);

  const toggleMember = (userId: string) => {
    setSelectedIds((prev) =>
      prev.includes(userId) ? prev.filter((id) => id !== userId) : [...prev, userId]
    );
  };

  const handleCreate = async () => {
    if (!groupName.trim() || creating) return;
    setCreating(true);
    try {
      await im.group.create(groupName.trim(), 0, selectedIds);
      toast("群创建成功");
      navigate("/chat");
    } catch {
      toast("创建群失败");
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="flex flex-1 flex-col">
      {/* Header */}
      <div className="flex items-center gap-3 border-b px-4 py-3">
        <Button variant="ghost" size="icon" onClick={() => navigate("/chat")}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <div>
          <div className="text-sm font-medium">创建群</div>
          <div className="text-xs text-muted-foreground">选择成员并设置群名称</div>
        </div>
      </div>

      <ScrollArea className="flex-1 p-4">
        {/* Group info inputs */}
        <div className="space-y-3">
          <Input
            placeholder="群名称（必填）"
            value={groupName}
            onChange={(e) => setGroupName(e.target.value)}
          />
          <Input
            placeholder="群头像 URL（可选）"
            value={faceUrl}
            onChange={(e) => setFaceUrl(e.target.value)}
          />
        </div>

        <Separator className="my-4" />

        {/* Member selection */}
        <div className="mb-3 text-sm font-medium text-muted-foreground">
          选择初始成员（{selectedIds.length} / {state.friends.length}）
        </div>

        {state.friends.length === 0 && (
          <div className="py-8 text-center text-sm text-muted-foreground">
            暂无好友，请先添加好友后再创建群
          </div>
        )}

        <div className="space-y-1">
          {state.friends.map((friend) => {
            const uid = friend.friendUserId;
            const selected = selectedIds.includes(uid);
            return (
              <button
                key={uid}
                onClick={() => toggleMember(uid)}
                className={cn(
                  "flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left transition-colors hover:bg-accent",
                  selected && "bg-accent"
                )}
              >
                <Avatar className="h-9 w-9">
                  <AvatarFallback className="text-xs">
                    {(friend.nickname || uid).charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <div className="flex-1">
                  <div className="text-sm font-medium">{friend.nickname || uid}</div>
                  <div className="text-xs text-muted-foreground">ID: {uid}</div>
                </div>
                {selected && <Check className="h-4 w-4 text-primary" />}
              </button>
            );
          })}
        </div>
      </ScrollArea>

      {/* Bottom bar */}
      <div className="border-t p-3">
        <Button
          className="w-full"
          onClick={handleCreate}
          disabled={!groupName.trim() || creating}
        >
          {creating ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <UserPlus className="mr-2 h-4 w-4" />}
          创建群（{selectedIds.length} 人）
        </Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Verify build**

Run: `cd im-web && npx tsc --noEmit`
Expected: No type errors

### Task 4: GroupInfoPage — Group Details and Member Management

**Files:**
- Create: `im-web/src/pages/GroupInfoPage.tsx`

- [ ] **Step 1: Create the page component**

```tsx
import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore, type GroupMember } from "@/store/store";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import {
  ArrowLeft, Crown, Shield, UserMinus, Trash2, LogOut, Loader2,
} from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";

function roleLabel(level: number): { text: string; className: string } | null {
  if (level >= 200) return { text: "群主", className: "text-red-500 bg-red-50 border-red-200" };
  if (level >= 100) return { text: "管理员", className: "text-blue-500 bg-blue-50 border-blue-200" };
  return null;
}

export default function GroupInfoPage() {
  const { groupId } = useParams<{ groupId: string }>();
  const navigate = useNavigate();
  const { state, fetchGroupMembers, fetchGroupInfo } = useStore();
  const [currentUserRole, setCurrentUserRole] = useState<number>(1);
  const [loading, setLoading] = useState(true);
  const [kicking, setKicking] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (!groupId) return;
    Promise.all([
      fetchGroupInfo(groupId),
      fetchGroupMembers(groupId),
    ]).then(() => setLoading(false));
  }, [groupId, fetchGroupInfo, fetchGroupMembers]);

  const groupInfo = groupId ? state.groupInfoCache[groupId] : undefined;
  const members = groupId ? state.groupMembers[groupId] || [] : [];

  // Determine current user's role
  useEffect(() => {
    if (!state.userId) return;
    const me = members.find((m) => m.userId === state.userId);
    if (me) setCurrentUserRole(me.roleLevel);
  }, [members, state.userId]);

  const isOwner = currentUserRole >= 200;
  const isAdmin = currentUserRole >= 100;

  const handleKick = async (userId: string) => {
    if (!groupId) return;
    setKicking((prev) => ({ ...prev, [userId]: true }));
    try {
      await im.group.kick(groupId, userId);
      await fetchGroupMembers(groupId);
      toast("已踢出");
    } catch {
      toast("踢出失败");
    } finally {
      setKicking((prev) => ({ ...prev, [userId]: false }));
    }
  };

  const handleDisband = async () => {
    if (!groupId) return;
    try {
      await im.group.disband(groupId);
      toast("群已解散");
      navigate("/chat");
    } catch {
      toast("解散失败");
    }
  };

  const handleQuit = async () => {
    if (!groupId) return;
    try {
      await im.group.quit(groupId);
      toast("已退出群");
      navigate("/chat");
    } catch {
      toast("退出失败");
    }
  };

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!groupInfo) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-sm text-muted-foreground">群不存在</p>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col">
      {/* Header */}
      <div className="flex items-center gap-3 border-b px-4 py-3">
        <Button variant="ghost" size="icon" onClick={() => navigate("/chat")}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <div>
          <div className="text-sm font-medium">群信息</div>
          <div className="text-xs text-muted-foreground">{groupInfo.groupName}</div>
        </div>
      </div>

      <ScrollArea className="flex-1 p-4">
        {/* Group basic info */}
        <div className="flex flex-col items-center py-6">
          <Avatar className="mb-3 h-16 w-16">
            <AvatarFallback className="text-lg">
              {groupInfo.groupName.charAt(0).toUpperCase()}
            </AvatarFallback>
          </Avatar>
          <h2 className="text-lg font-semibold">{groupInfo.groupName}</h2>
          <p className="text-xs text-muted-foreground">ID: {groupInfo.groupId}</p>
          <p className="mt-1 text-xs text-muted-foreground">
            群主: {groupInfo.ownerUserId} · {groupInfo.memberCount || members.length} 人
          </p>
        </div>

        <Separator />

        {/* Member list */}
        <div className="py-3">
          <h3 className="mb-2 text-sm font-medium">成员列表（{members.length}）</h3>
          <div className="space-y-1">
            {members.map((member) => {
              const label = roleLabel(member.roleLevel);
              const canKick =
                isOwner || (isAdmin && member.roleLevel < 100);
              return (
                <div
                  key={member.userId}
                  className="flex items-center justify-between rounded-lg px-3 py-2 transition-colors hover:bg-accent"
                >
                  <div className="flex items-center gap-3">
                    <Avatar className="h-9 w-9">
                      <AvatarFallback className="text-xs">
                        {(member.nickname || member.userId).charAt(0).toUpperCase()}
                      </AvatarFallback>
                    </Avatar>
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium">
                          {member.nickname || member.userId}
                        </span>
                        {member.roleLevel >= 200 && <Crown className="h-3.5 w-3.5 text-red-500" />}
                        {label && (
                          <span className={`rounded border px-1.5 text-[10px] ${label.className}`}>
                            {label.text}
                          </span>
                        )}
                      </div>
                      <div className="text-xs text-muted-foreground">ID: {member.userId}</div>
                    </div>
                  </div>

                  {canKick && member.userId !== state.userId && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-destructive"
                      onClick={() => handleKick(member.userId)}
                      disabled={!!kicking[member.userId]}
                    >
                      {kicking[member.userId] ? (
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                      ) : (
                        <UserMinus className="h-3.5 w-3.5" />
                      )}
                    </Button>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        <Separator />

        {/* Group actions */}
        <div className="space-y-2 py-4">
          {isOwner && (
            <Button
              variant="destructive"
              className="w-full"
              onClick={handleDisband}
            >
              <Trash2 className="mr-2 h-4 w-4" />
              解散群
            </Button>
          )}
          {!isOwner && (
            <Button
              variant="outline"
              className="w-full text-destructive"
              onClick={handleQuit}
            >
              <LogOut className="mr-2 h-4 w-4" />
              退出群
            </Button>
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
```

- [ ] **Step 2: Verify build**

Run: `cd im-web && npx tsc --noEmit`
Expected: No type errors

### Task 5: UserProfilePage — User Profile

**Files:**
- Create: `im-web/src/pages/UserProfilePage.tsx`

- [ ] **Step 1: Create the page component**

```tsx
import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { ArrowLeft, MessageCircle, UserMinus, Ban, UserPlus, Loader2 } from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import type { UserInfo } from "im-sdk";

export default function UserProfilePage() {
  const { userId } = useParams<{ userId: string }>();
  const navigate = useNavigate();
  const { state, fetchUserProfile, removeFriend } = useStore();
  const [profile, setProfile] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!userId) return;
    setLoading(true);
    im.user.info(userId).then((info) => {
      setProfile(info as unknown as UserInfo);
    }).catch(() => {
      toast("获取用户信息失败");
    }).finally(() => {
      setLoading(false);
    });
  }, [userId, fetchUserProfile]);

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!profile || !userId) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-sm text-muted-foreground">用户不存在</p>
      </div>
    );
  }

  const isSelf = userId === state.userId;
  const isFriend = state.friends.some((f) => f.friendUserId === userId);

  const handleSendMessage = () => {
    navigate("/chat");
    // Find or create conversation with this user
  };

  const handleRemoveFriend = async () => {
    try {
      await removeFriend(userId);
      toast("已删除好友");
      navigate("/chat");
    } catch {
      toast("操作失败");
    }
  };

  const handleBlack = async () => {
    try {
      await im.friend.black(userId);
      toast("已拉黑");
      navigate("/chat");
    } catch {
      toast("操作失败");
    }
  };

  const handleApplyFriend = async () => {
    try {
      await im.friend.apply(userId);
      toast("好友申请已发送");
    } catch {
      toast("操作失败");
    }
  };

  return (
    <div className="flex flex-1 flex-col">
      {/* Header */}
      <div className="flex items-center gap-3 border-b px-4 py-3">
        <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <span className="text-sm font-medium">用户信息</span>
      </div>

      <div className="flex flex-1 flex-col items-center justify-center px-6">
        {/* User avatar and info */}
        <Avatar className="mb-4 h-20 w-20">
          <AvatarFallback className="text-xl">
            {(profile.nickname || userId).charAt(0).toUpperCase()}
          </AvatarFallback>
        </Avatar>
        <h2 className="text-xl font-semibold">{profile.nickname || userId}</h2>
        <p className="text-sm text-muted-foreground">ID: {userId}</p>
        {profile.appMangerLevel !== undefined && profile.appMangerLevel > 0 && (
          <span className="mt-1 rounded bg-yellow-100 px-2 py-0.5 text-xs text-yellow-700">
            平台管理员
          </span>
        )}

        <Separator className="my-6" />

        {/* Actions */}
        <div className="flex w-full max-w-xs flex-col gap-2">
          {isSelf && (
            <Button variant="outline" className="w-full" disabled>
              编辑资料（待实现）
            </Button>
          )}

          {isFriend && (
            <>
              <Button className="w-full" onClick={handleSendMessage}>
                <MessageCircle className="mr-2 h-4 w-4" />
                发消息
              </Button>
              <Button variant="outline" className="w-full text-destructive" onClick={handleRemoveFriend}>
                <UserMinus className="mr-2 h-4 w-4" />
                删除好友
              </Button>
              <Button variant="outline" className="w-full text-destructive" onClick={handleBlack}>
                <Ban className="mr-2 h-4 w-4" />
                拉黑
              </Button>
            </>
          )}

          {!isSelf && !isFriend && (
            <Button className="w-full" onClick={handleApplyFriend}>
              <UserPlus className="mr-2 h-4 w-4" />
              加好友
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Verify build**

Run: `cd im-web && npx tsc --noEmit`
Expected: No type errors

### Task 6: ChatArea Enhancements — Group Chat, Message Revoke, History Loading, Header Navigation

**Files:**
- Modify: `im-web/src/components/ChatArea.tsx`

- [ ] **Step 1: Rewrite ChatArea with all enhancements**

```tsx
import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import { Send, Paperclip, MoreHorizontal, Undo2, Info } from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";

export default function ChatArea() {
  const { state, sendMessage } = useStore();
  const [input, setInput] = useState("");
  const navigate = useNavigate();

  const conv = state.conversations.find(
    (c) => c.conversationId === state.activeConversationId
  );
  const messages = conv ? state.messages[conv.conversationId] || [] : [];

  // Load history when conversation changes
  useEffect(() => {
    if (!conv?.conversationId) return;
    const loadHistory = async () => {
      try {
        const maxSeq = await im.message.seq(conv.conversationId);
        if (maxSeq > 0) {
          const from = Math.max(0, maxSeq - 20);
          await im.message.pull(conv.conversationId, from, maxSeq);
        }
      } catch {
        // silent — history loading is best-effort
      }
    };
    loadHistory();
  }, [conv?.conversationId]);

  const handleSend = () => {
    if (!input.trim() || !conv) return;

    if (conv.conversationType === 2 && conv.groupId) {
      // Group chat
      im.message.sendGroup(conv.groupId, "1", input.trim()).catch(() => {
        toast("发送失败");
      });
    } else if (conv.userId) {
      // Single chat
      sendMessage(conv.userId, input.trim());
    }
    setInput("");
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleRevoke = useCallback(async (messageId: string) => {
    try {
      await im.message.revoke(messageId);
      toast("已撤回");
    } catch {
      toast("撤回失败");
    }
  }, []);

  const handleHeaderClick = () => {
    if (!conv) return;
    if (conv.conversationType === 2 && conv.groupId) {
      navigate(`/chat/group/${conv.groupId}`);
    } else if (conv.userId) {
      navigate(`/chat/user/${conv.userId}`);
    }
  };

  // Empty state
  if (!state.activeConversationId) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="text-center">
          <h2 className="text-lg font-semibold text-muted-foreground">
            选择一个会话开始聊天
          </h2>
          <p className="mt-1 text-sm text-muted-foreground/60">
            从左侧选择好友或群组
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col">
      {/* Chat Header — clickable to navigate to group/user info */}
      <button
        onClick={handleHeaderClick}
        className="flex items-center gap-3 border-b px-4 py-3 text-left transition-colors hover:bg-accent/50"
      >
        <Avatar className="h-9 w-9">
          <AvatarImage src={conv?.faceUrl} />
          <AvatarFallback>
            {(conv?.showName || "?").charAt(0).toUpperCase()}
          </AvatarFallback>
        </Avatar>
        <div className="flex-1">
          <div className="text-sm font-medium">{conv?.showName}</div>
          <div className="text-xs text-muted-foreground">
            {conv?.conversationType === 2 ? "群聊" : "单聊"}
          </div>
        </div>
        <Info className="h-4 w-4 text-muted-foreground" />
      </button>

      {/* Messages */}
      <ScrollArea className="flex-1 p-4">
        {messages.length === 0 && (
          <div className="flex h-full items-center justify-center">
            <p className="text-sm text-muted-foreground">暂无消息，发送第一条消息吧</p>
          </div>
        )}

        <div className="space-y-3">
          {messages.map((msg) => {
            const isMine = msg.senderUserId === state.userId;
            // Check if message is revoked (contentType could indicate revocation)
            const isRevoked = msg.contentType === 101 || msg.content === "消息已撤回";
            if (isRevoked) {
              return (
                <div key={msg.messageId} className="flex justify-center">
                  <span className="rounded bg-muted px-3 py-1 text-xs text-muted-foreground">
                    {isMine ? "你" : msg.senderNickname || msg.senderUserId} 撤回了一条消息
                  </span>
                </div>
              );
            }
            return (
              <div
                key={msg.messageId}
                className={`flex ${isMine ? "justify-end" : "justify-start"}`}
              >
                <div className="group flex max-w-[70%] flex-col">
                  {/* Message bubble */}
                  <div className="flex items-end gap-1">
                    {!isMine && (
                      <Avatar className="mb-1 h-6 w-6 cursor-pointer"
                        onClick={() => navigate(`/chat/user/${msg.senderUserId}`)}>
                        <AvatarFallback className="text-[10px]">
                          {(msg.senderNickname || msg.senderUserId).charAt(0).toUpperCase()}
                        </AvatarFallback>
                      </Avatar>
                    )}
                    <div
                      className={`rounded-lg px-3 py-2 text-sm ${
                        isMine
                          ? "bg-primary text-primary-foreground"
                          : "bg-secondary text-secondary-foreground"
                      }`}
                    >
                      {!isMine && (
                        <div className="mb-1 text-xs opacity-70">
                          {msg.senderNickname || msg.senderUserId}
                        </div>
                      )}
                      <div>{msg.content}</div>
                      <div
                        className={`mt-1 text-[10px] ${
                          isMine ? "text-primary-foreground/60" : "text-muted-foreground"
                        }`}
                      >
                        {formatMsgTime(msg.createTime)}
                        {isMine && (msg.status === 0 ? " 发送中..." : msg.status === 1 ? " ✓" : " ✓✓")}
                      </div>
                    </div>

                    {/* Revoke button (own messages only) */}
                    {isMine && (
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <button className="invisible rounded p-1 text-muted-foreground opacity-0 transition-all hover:bg-accent group-hover:visible group-hover:opacity-100">
                            <MoreHorizontal className="h-3.5 w-3.5" />
                          </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" side="top">
                          <DropdownMenuItem onClick={() => handleRevoke(msg.messageId)}>
                            <Undo2 className="mr-2 h-4 w-4" />
                            撤回
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </ScrollArea>

      {/* Input */}
      <div className="border-t p-3">
        <div className="flex items-center gap-2">
          <button className="rounded-md p-2 hover:bg-accent">
            <Paperclip className="h-4 w-4 text-muted-foreground" />
          </button>
          <Input
            placeholder="输入消息..."
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            className="flex-1"
          />
          <Button size="icon" onClick={handleSend} disabled={!input.trim()}>
            <Send className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  );
}

function formatMsgTime(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
}
```

- [ ] **Step 2: Verify build**

Run: `cd im-web && npx tsc --noEmit`
Expected: No type errors

### Task 7: Sidebar — Add Create Group Button

**Files:**
- Modify: `im-web/src/components/Sidebar.tsx`

- [ ] **Step 1: Add "create group" button in the ChatList quick actions area**

Find the quick actions div in the ChatList component (around line 122):
```tsx
<div className="flex gap-1 border-b px-3 py-2">
```

Replace with:
```tsx
<div className="flex gap-1 border-b px-3 py-2">
  <TooltipProvider>
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          onClick={onSearchUser}
          className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-secondary/80"
        >
          <UserPlus className="mr-1 inline h-3 w-3" /> 加好友
        </button>
      </TooltipTrigger>
      <TooltipContent>搜索并添加好友</TooltipContent>
    </Tooltip>
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          onClick={onSearchGroup}
          className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-secondary/80"
        >
          <Users className="mr-1 inline h-3 w-3" /> 加群
        </button>
      </TooltipTrigger>
      <TooltipContent>搜索并加入群组</TooltipContent>
    </Tooltip>
  </TooltipProvider>
</div>
```

Add a new tooltip import if not already there — add `Plus` to the lucide-react imports at the top of the file:
Find the import: `import { MessageCircle, Users, UserPlus, MoreHorizontal, UserMinus, } from "lucide-react";`
Replace with: `import { MessageCircle, Users, UserPlus, Plus, MoreHorizontal, UserMinus, } from "lucide-react";`

Modify the ChatList to accept an `onCreateGroup` prop, and add a third button:

```tsx
function ChatList({
  onSearchUser,
  onSearchGroup,
  onCreateGroup,
}: {
  onSearchUser: () => void;
  onSearchGroup: () => void;
  onCreateGroup: () => void;
}) {
```

Add the button in the actions div, after the "加群" button:
```tsx
<Tooltip>
  <TooltipTrigger asChild>
    <button
      onClick={onCreateGroup}
      className="flex-1 rounded-md bg-secondary px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-secondary/80"
    >
      <Plus className="mr-1 inline h-3 w-3" /> 创建群
    </button>
  </TooltipTrigger>
  <TooltipContent>创建一个新群</TooltipContent>
</Tooltip>
```

Pass the handler from the Sidebar component:
```tsx
// In Sidebar, add the navigate hook
import { useNavigate } from "react-router-dom";

export default function Sidebar() {
  const { state } = useStore();
  const navigate = useNavigate();
  // ...existing code...

  // Pass onCreateGroup to ChatList
  <ChatList
    onSearchUser={() => setSearchUserOpen(true)}
    onSearchGroup={() => setSearchGroupOpen(true)}
    onCreateGroup={() => navigate("/chat/create-group")}
  />
```

- [ ] **Step 2: Verify build**

Run: `cd im-web && npx tsc --noEmit`
Expected: No type errors

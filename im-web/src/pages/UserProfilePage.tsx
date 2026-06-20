import { ChangeEvent, useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Ban, Camera, Loader2, MessageCircle, Shield, UserMinus, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { getErrorText, type UserInfo } from "im-sdk";
import { AppPage, Surface } from "@/components/AppPage";
import { LoadingState, StateBadge, StatusDot } from "@/components/design-system";

export default function UserProfilePage() {
  const { userId } = useParams<{ userId: string }>();
  const navigate = useNavigate();
  const { state, dispatch, applyFriend, fetchFriends, openSingleChat, removeFriend } = useStore();
  const [profile, setProfile] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [friendshipChecked, setFriendshipChecked] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [nickname, setNickname] = useState("");
  const [avatarFile, setAvatarFile] = useState<File | null>(null);
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!userId) return;
    let cancelled = false;
    setLoading(true);
    setFriendshipChecked(userId === state.userId);

    const loadProfile = userId === state.userId ? im.user.me() : im.user.info(userId);
    const loadFriendship = userId === state.userId ? Promise.resolve() : fetchFriends();

    Promise.all([loadProfile, loadFriendship])
      .then(([info]) => {
        if (cancelled) return;
        setProfile(info as unknown as UserInfo);
        dispatch({ type: "SET_USER_PROFILE", userId, info: info as unknown as UserInfo });
        setFriendshipChecked(true);
      })
      .catch((err) => {
        if (cancelled) return;
        toast(`获取用户信息失败：${getErrorText(err)}`);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [userId, state.userId, dispatch, fetchFriends]);

  useEffect(() => {
    if (!editOpen || !profile) return;
    setNickname(profile.nickname || "");
    setAvatarFile(null);
    setAvatarPreview(null);
  }, [editOpen, profile]);

  useEffect(() => {
    return () => {
      if (avatarPreview) URL.revokeObjectURL(avatarPreview);
    };
  }, [avatarPreview]);

  if (loading) {
    return <LoadingState text="正在读取用户资料" />;
  }

  if (!profile || !userId) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-sm text-slate-500">用户不存在</p>
      </div>
    );
  }

  const isSelf = userId === state.userId;
  const isFriend = state.friends.some((f) => f.friendUserId === userId);
  const avatarSrc = avatarPreview || profile.faceUrl;

  const handleSendMessage = () => {
    if (!userId || !profile) return;
    openSingleChat({
      userId,
      nickname: profile.nickname || userId,
      faceUrl: profile.faceUrl,
    });
    navigate("/chat");
  };

  const handleRemoveFriend = async () => {
    try {
      await removeFriend(userId);
      toast("已删除好友");
      navigate("/chat");
    } catch (err) {
      toast(`删除失败：${getErrorText(err)}`);
    }
  };

  const handleBlack = async () => {
    try {
      await im.friend.black(userId);
      toast("已拉黑");
      navigate("/chat");
    } catch (err) {
      toast(`拉黑失败：${getErrorText(err)}`);
    }
  };

  const handleApplyFriend = async () => {
    if (isFriend) {
      toast("已经是好友");
      return;
    }
    try {
      await applyFriend(userId);
      toast("好友申请已发送");
    } catch (err) {
      const text = getErrorText(err);
      if (text.toLowerCase().includes("already friends")) {
        await fetchFriends();
        toast("已经是好友");
        return;
      }
      toast(`操作失败：${text}`);
    }
  };

  const renderActions = () => {
    if (!friendshipChecked) {
      return (
        <Button variant="outline" className="justify-start" disabled>
          <Loader2 className="h-4 w-4 animate-spin" />
          正在确认关系
        </Button>
      );
    }

    if (isSelf) {
      return (
        <Button variant="outline" className="justify-start" onClick={() => setEditOpen(true)}>
          <Camera className="h-4 w-4" />
          编辑资料
        </Button>
      );
    }

    if (isFriend) {
      return (
        <>
          <Button className="justify-start" onClick={handleSendMessage}>
            <MessageCircle className="h-4 w-4" />
            发消息
          </Button>
          <Button variant="outline" className="justify-start text-red-600 hover:border-red-200 hover:bg-red-50" onClick={handleRemoveFriend}>
            <UserMinus className="h-4 w-4" />
            删除好友
          </Button>
          <Button variant="outline" className="justify-start text-red-600 hover:border-red-200 hover:bg-red-50" onClick={handleBlack}>
            <Ban className="h-4 w-4" />
            拉黑
          </Button>
        </>
      );
    }

    return (
      <Button className="justify-start" onClick={handleApplyFriend}>
        <UserPlus className="h-4 w-4" />
        加好友
      </Button>
    );
  };

  const handleAvatarChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null;
    setAvatarFile(file);
    setAvatarPreview((current) => {
      if (current) URL.revokeObjectURL(current);
      return file ? URL.createObjectURL(file) : null;
    });
  };

  const handleSaveProfile = async () => {
    if (!profile || !userId) return;
    const nextNickname = nickname.trim();
    if (!nextNickname) {
      toast("昵称不能为空");
      return;
    }

    setSaving(true);
    try {
      let updated = profile;
      if (avatarFile) {
        updated = await im.user.updateAvatar(avatarFile, avatarFile.name || "avatar");
      }
      if (nextNickname !== (updated.nickname || "")) {
        updated = await im.user.updateProfile({ nickname: nextNickname });
      }
      setProfile(updated);
      dispatch({ type: "SET_USER_PROFILE", userId, info: updated as unknown as UserInfo });
      setEditOpen(false);
      toast("资料已更新");
    } catch {
      toast("保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <AppPage title="用户信息" description={profile.nickname || userId} onBack={() => navigate(-1)}>
      <div className="mx-auto flex h-full w-full max-w-3xl flex-col gap-4 px-5 py-5">
        <Surface className="overflow-hidden">
          <div className="border-b border-slate-100 bg-white px-5 py-5">
            <div className="flex items-center gap-4">
              <Avatar className="h-20 w-20 border border-slate-200 shadow-sm">
                {profile.faceUrl && <AvatarImage src={profile.faceUrl} alt={profile.nickname || userId} />}
                <AvatarFallback className="bg-blue-100 text-2xl font-semibold text-blue-700">
                  {(profile.nickname || userId).charAt(0).toUpperCase()}
                </AvatarFallback>
              </Avatar>
              <div className="min-w-0">
                <h2 className="truncate text-xl font-semibold text-[var(--text-strong)]">{profile.nickname || userId}</h2>
                <p className="mt-1 truncate text-sm text-[var(--text-muted)]">ID: {userId}</p>
                <div className="mt-2 flex flex-wrap gap-2">
                  {isSelf ? <StateBadge tone="info">我的账号</StateBadge> : isFriend ? <StateBadge tone="online"><StatusDot tone="online" /> 好友</StateBadge> : <StateBadge>未添加</StateBadge>}
                </div>
                {profile.appMangerLevel !== undefined && profile.appMangerLevel !== "NORMAL" && (
                  <span className="mt-2 inline-flex items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-xs text-amber-700">
                    <Shield className="h-3 w-3" />
                    平台管理员
                  </span>
                )}
              </div>
            </div>
          </div>

          <div className="grid gap-3 px-5 py-4 sm:grid-cols-2">
            <InfoItem label="昵称" value={profile.nickname || "-"} />
            <InfoItem label="用户 ID" value={userId} />
          </div>
        </Surface>

        <Surface className="p-4">
          <div className="mb-3 text-sm font-semibold text-slate-900">{isSelf ? "账号操作" : isFriend ? "好友操作" : "添加好友"}</div>
          <div className="grid gap-2 sm:grid-cols-2">
            {renderActions()}
          </div>
        </Surface>
      </div>

      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>编辑资料</DialogTitle>
            <DialogDescription>修改头像和昵称后会同步到你的个人资料。</DialogDescription>
          </DialogHeader>

          <div className="space-y-4 px-5 pb-5">
            <div className="flex justify-center">
              <label className="relative cursor-pointer">
                <Avatar className="h-20 w-20 border border-white shadow-lg ring-1 ring-slate-200">
                  {avatarSrc && <AvatarImage src={avatarSrc} alt={nickname || userId} />}
                  <AvatarFallback className="bg-slate-100 text-xl font-semibold text-slate-700">
                    {(nickname || userId).charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <span className="absolute bottom-0 right-0 flex h-7 w-7 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-700 shadow-sm">
                  <Camera className="h-4 w-4" />
                </span>
                <input
                  className="sr-only"
                  type="file"
                  accept="image/*"
                  onChange={handleAvatarChange}
                />
              </label>
            </div>

            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700" htmlFor="profile-nickname">
                昵称
              </label>
              <Input
                id="profile-nickname"
                value={nickname}
                maxLength={32}
                onChange={(event) => setNickname(event.target.value)}
              />
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setEditOpen(false)} disabled={saving}>
              取消
            </Button>
            <Button onClick={handleSaveProfile} disabled={saving}>
              {saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </AppPage>
  );
}

function InfoItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md bg-slate-50 px-3 py-2 ring-1 ring-slate-100">
      <div className="text-xs text-slate-500">{label}</div>
      <div className="mt-1 truncate text-sm font-medium text-slate-900">{value}</div>
    </div>
  );
}

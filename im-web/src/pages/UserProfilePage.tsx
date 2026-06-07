import { ChangeEvent, useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { ArrowLeft, MessageCircle, UserMinus, Ban, UserPlus, Loader2, Camera } from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import type { UserInfo } from "im-sdk";

export default function UserProfilePage() {
  const { userId } = useParams<{ userId: string }>();
  const navigate = useNavigate();
  const { state, dispatch, fetchUserProfile, removeFriend } = useStore();
  const [profile, setProfile] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [editOpen, setEditOpen] = useState(false);
  const [nickname, setNickname] = useState("");
  const [avatarFile, setAvatarFile] = useState<File | null>(null);
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!userId) return;
    setLoading(true);
    const request = userId === state.userId ? im.user.me() : im.user.info(userId);
    request.then((info) => {
      setProfile(info as unknown as UserInfo);
      dispatch({ type: "SET_USER_PROFILE", userId, info: info as unknown as UserInfo });
    }).catch(() => {
      toast("获取用户信息失败");
    }).finally(() => {
      setLoading(false);
    });
  }, [userId, state.userId, dispatch, fetchUserProfile]);

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
  const avatarSrc = avatarPreview || profile.faceUrl;

  const handleSendMessage = () => {
    navigate("/chat");
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
          {profile.faceUrl && <AvatarImage src={profile.faceUrl} alt={profile.nickname || userId} />}
          <AvatarFallback className="text-xl">
            {(profile.nickname || userId).charAt(0).toUpperCase()}
          </AvatarFallback>
        </Avatar>
        <h2 className="text-xl font-semibold">{profile.nickname || userId}</h2>
        <p className="text-sm text-muted-foreground">ID: {userId}</p>
        {profile.appMangerLevel !== undefined && profile.appMangerLevel !== "NORMAL" && (
          <span className="mt-1 rounded bg-yellow-100 px-2 py-0.5 text-xs text-yellow-700">
            平台管理员
          </span>
        )}

        <Separator className="my-6" />

        {/* Actions */}
        <div className="flex w-full max-w-xs flex-col gap-2">
          {isSelf && (
            <Button variant="outline" className="w-full" onClick={() => setEditOpen(true)}>
              编辑资料
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

      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>编辑资料</DialogTitle>
          </DialogHeader>

          <div className="space-y-4">
            <div className="flex justify-center">
              <label className="relative cursor-pointer">
                <Avatar className="h-20 w-20 border">
                  {avatarSrc && <AvatarImage src={avatarSrc} alt={nickname || userId} />}
                  <AvatarFallback className="text-xl">
                    {(nickname || userId).charAt(0).toUpperCase()}
                  </AvatarFallback>
                </Avatar>
                <span className="absolute bottom-0 right-0 flex h-7 w-7 items-center justify-center rounded-full border bg-background shadow-sm">
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
              <label className="text-sm font-medium" htmlFor="profile-nickname">
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
    </div>
  );
}

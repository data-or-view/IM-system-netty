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
        {profile.appMangerLevel !== undefined && profile.appMangerLevel !== "NORMAL" && (
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

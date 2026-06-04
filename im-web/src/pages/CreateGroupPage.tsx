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
  const { state, dispatch, fetchConversations, fetchMyGroups } = useStore();
  const [groupName, setGroupName] = useState("");
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
      const group = await im.group.create(groupName.trim(), 0, selectedIds);
      dispatch({ type: "SET_MY_GROUPS", list: [group, ...state.myGroups.filter((item) => item.groupId !== group.groupId)] });
      await Promise.all([fetchMyGroups(), fetchConversations()]);
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
        </div>

        <Separator className="my-4" />

        {/* Member selection (optional) */}
        <div className="mb-3 text-sm font-medium text-muted-foreground">
          选择初始成员（可选，{selectedIds.length} 人）
        </div>

        {state.friends.length === 0 ? (
          <div className="py-8 text-center text-sm text-muted-foreground">
            暂无好友，可以先创建群，后续再邀请成员
          </div>
        ) : (
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
      )}
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

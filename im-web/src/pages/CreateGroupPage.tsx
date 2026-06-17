import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useStore } from "@/store/store";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Check, Loader2, ShieldCheck, UserPlus, Users } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { im } from "@/sdk/im-sdk";
import { GroupJoinVerification, GroupType, getErrorText } from "im-sdk";
import { AppPage, Surface } from "@/components/AppPage";

export default function CreateGroupPage() {
  const navigate = useNavigate();
  const { state, dispatch, fetchConversations, fetchMyGroups, openGroupChat } = useStore();
  const [groupName, setGroupName] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [needVerification, setNeedVerification] = useState(true);
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
      const group = await im.group.create(groupName.trim(), GroupType.PRIVATE, selectedIds, needVerification ? GroupJoinVerification.NEED_APPROVAL : GroupJoinVerification.DIRECT);
      dispatch({ type: "SET_MY_GROUPS", list: [group, ...state.myGroups.filter((item) => item.groupId !== group.groupId)] });
      await Promise.all([fetchMyGroups(), fetchConversations()]);
      openGroupChat({ groupId: group.groupId, groupName: group.groupName, faceUrl: group.faceUrl });
      toast("群创建成功");
      navigate("/chat");
    } catch (err) {
      toast(`创建群失败：${getErrorText(err)}`);
    } finally {
      setCreating(false);
    }
  };

  return (
    <AppPage
      title="创建群"
      description="设置群资料并选择初始成员"
      onBack={() => navigate("/chat")}
      footer={(
        <Button
          className="w-full"
          onClick={handleCreate}
          disabled={!groupName.trim() || creating}
        >
          {creating ? <Loader2 className="h-4 w-4 animate-spin" /> : <UserPlus className="h-4 w-4" />}
          创建群（{selectedIds.length} 人）
        </Button>
      )}
    >
      <ScrollArea className="h-full">
        <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-5 py-5">
          <Surface className="p-4">
            <div className="mb-3 flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-md bg-slate-900 text-white">
                <Users className="h-5 w-5" />
              </div>
              <div>
                <div className="text-sm font-semibold">群资料</div>
                <div className="text-xs text-slate-500">群名称创建后仍可在群资料里维护</div>
              </div>
            </div>
            <Input
              placeholder="群名称（必填）"
              value={groupName}
              onChange={(e) => setGroupName(e.target.value)}
            />
          </Surface>

          <button
            type="button"
            onClick={() => setNeedVerification((prev) => !prev)}
            className="flex w-full items-center justify-between gap-3 rounded-lg border border-slate-200 bg-white px-4 py-3 text-left text-sm shadow-sm transition-colors hover:border-slate-300 hover:bg-slate-50"
          >
            <span className="flex min-w-0 items-center gap-3">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-emerald-50 text-emerald-700">
                <ShieldCheck className="h-5 w-5" />
              </span>
              <span className="min-w-0">
                <span className="block font-semibold text-slate-900">入群审批</span>
                <span className="text-xs text-slate-500">
                  {needVerification ? "新成员需要群主或管理员同意后入群" : "新成员可以直接加入群聊"}
                </span>
              </span>
            </span>
            <span
              className={cn(
                "shrink-0 rounded-full px-2.5 py-1 text-xs font-medium",
                needVerification ? "bg-slate-900 text-white" : "bg-slate-100 text-slate-500"
              )}
            >
              {needVerification ? "已开启" : "已关闭"}
            </span>
          </button>

          <Surface>
            <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
              <div>
                <div className="text-sm font-semibold">选择初始成员</div>
                <div className="text-xs text-slate-500">可选，已选择 {selectedIds.length} 人</div>
              </div>
            </div>

            {state.friends.length === 0 ? (
              <div className="px-4 py-10 text-center text-sm text-slate-500">
                暂无好友，可以先创建群，后续再邀请成员。
              </div>
            ) : (
              <div className="divide-y divide-slate-100">
                {state.friends.map((friend) => {
                  const uid = friend.friendUserId;
                  const selected = selectedIds.includes(uid);
                  return (
                    <button
                      key={uid}
                      onClick={() => toggleMember(uid)}
                      className={cn(
                        "flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-slate-50",
                        selected && "bg-slate-50"
                      )}
                    >
                      <Avatar className="h-10 w-10 border border-white shadow-sm">
                        <AvatarFallback className="bg-slate-100 text-sm font-semibold text-slate-700">
                          {(friend.nickname || uid).charAt(0).toUpperCase()}
                        </AvatarFallback>
                      </Avatar>
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-semibold text-slate-900">{friend.nickname || uid}</div>
                        <div className="truncate text-xs text-slate-500">ID: {uid}</div>
                      </div>
                      <span className={cn(
                        "flex h-6 w-6 items-center justify-center rounded-full border text-xs",
                        selected ? "border-slate-900 bg-slate-900 text-white" : "border-slate-200 bg-white text-transparent"
                      )}>
                        <Check className="h-3.5 w-3.5" />
                      </span>
                    </button>
                  );
                })}
              </div>
            )}
          </Surface>
        </div>
      </ScrollArea>
    </AppPage>
  );
}

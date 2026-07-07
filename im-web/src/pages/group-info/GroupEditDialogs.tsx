import type { Dispatch, FormEvent, ReactNode, SetStateAction } from "react";
import { Loader2 } from "lucide-react";
import { GroupJoinVerification, type GroupJoinVerificationValue } from "im-sdk";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

export interface GroupInfoFormState {
  groupName: string;
  faceUrl: string;
  notification: string;
  introduction: string;
  needVerification: GroupJoinVerificationValue;
}

interface GroupEditDialogsProps {
  infoOpen: boolean;
  onInfoOpenChange: (open: boolean) => void;
  infoForm: GroupInfoFormState;
  setInfoForm: Dispatch<SetStateAction<GroupInfoFormState>>;
  savingInfo: boolean;
  onSaveGroupInfo: (event: FormEvent<HTMLFormElement>) => void;
  nicknameOpen: boolean;
  onNicknameOpenChange: (open: boolean) => void;
  nickname: string;
  onNicknameChange: (nickname: string) => void;
  savingNickname: boolean;
  onSaveNickname: (event: FormEvent<HTMLFormElement>) => void;
  currentUserId: string | null;
}

export function GroupEditDialogs({
  infoOpen,
  onInfoOpenChange,
  infoForm,
  setInfoForm,
  savingInfo,
  onSaveGroupInfo,
  nicknameOpen,
  onNicknameOpenChange,
  nickname,
  onNicknameChange,
  savingNickname,
  onSaveNickname,
  currentUserId,
}: GroupEditDialogsProps) {
  return (
    <>
      <Dialog open={infoOpen} onOpenChange={onInfoOpenChange}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>编辑群资料</DialogTitle>
            <DialogDescription>群名称、公告和入群方式会同步给群成员。</DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={onSaveGroupInfo}>
            <Field label="群名称">
              <Input
                value={infoForm.groupName}
                onChange={(event) => setInfoForm((prev) => ({ ...prev, groupName: event.target.value }))}
                maxLength={40}
              />
            </Field>
            <Field label="群头像 URL">
              <Input
                value={infoForm.faceUrl}
                onChange={(event) => setInfoForm((prev) => ({ ...prev, faceUrl: event.target.value }))}
                placeholder="https://..."
              />
            </Field>
            <Field label="入群方式">
              <select
                className="h-10 w-full rounded-md border border-slate-200 bg-white px-3 text-sm outline-none transition-colors focus:border-slate-400"
                value={infoForm.needVerification}
                onChange={(event) =>
                  setInfoForm((prev) => ({
                    ...prev,
                    needVerification: event.target.value as GroupJoinVerificationValue,
                  }))
                }
              >
                <option value={GroupJoinVerification.DIRECT}>可直接加入</option>
                <option value={GroupJoinVerification.NEED_APPROVAL}>需要审批</option>
                <option value={GroupJoinVerification.INVITE_ONLY}>仅邀请入群</option>
                <option value={GroupJoinVerification.FORBIDDEN}>禁止加入</option>
              </select>
            </Field>
            <Field label="群公告">
              <textarea
                className="min-h-20 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm outline-none transition-colors focus:border-slate-400"
                value={infoForm.notification}
                onChange={(event) => setInfoForm((prev) => ({ ...prev, notification: event.target.value }))}
                maxLength={500}
              />
            </Field>
            <Field label="群简介">
              <textarea
                className="min-h-20 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm outline-none transition-colors focus:border-slate-400"
                value={infoForm.introduction}
                onChange={(event) => setInfoForm((prev) => ({ ...prev, introduction: event.target.value }))}
                maxLength={500}
              />
            </Field>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onInfoOpenChange(false)}>
                取消
              </Button>
              <Button type="submit" disabled={savingInfo}>
                {savingInfo && <Loader2 className="h-4 w-4 animate-spin" />}
                保存
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={nicknameOpen} onOpenChange={onNicknameOpenChange}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>修改群昵称</DialogTitle>
            <DialogDescription>这个昵称只在当前群聊中使用。</DialogDescription>
          </DialogHeader>
          <form className="space-y-4" onSubmit={onSaveNickname}>
            <Field label="我的群昵称">
              <Input
                value={nickname}
                onChange={(event) => onNicknameChange(event.target.value)}
                maxLength={30}
                placeholder={currentUserId || "请输入群昵称"}
              />
            </Field>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onNicknameOpenChange(false)}>
                取消
              </Button>
              <Button type="submit" disabled={savingNickname}>
                {savingNickname && <Loader2 className="h-4 w-4 animate-spin" />}
                保存
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium text-slate-500">{label}</span>
      {children}
    </label>
  );
}

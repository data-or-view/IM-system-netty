import { useState, useEffect, useCallback, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { SYSTEM_CONVERSATION_ID, useStore } from "@/store/store";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import { MessageCircle, Send, Paperclip, MoreHorizontal, Undo2, Info, Phone, Video, PhoneOff } from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { MessageContentRenderer } from "@/components/MessageContentRenderer";
import SystemMessagePanel from "@/components/SystemMessagePanel";
import { EmptyState, MessageStatusIcon, PageHeader, StateBadge, StatusDot } from "@/components/design-system";
import {
  ConversationType,
  MessageContentType,
  GroupMemberRole,
  createClientMsgId,
  getErrorText,
  groupMemberRoleRank,
  type GroupCallSession,
  type OutgoingMessageContentTypeValue,
  type SendMessageAck,
} from "im-sdk";
import { useCall } from "@/components/call/CallProvider";
import { messageRenderKey, toLocalFailedMessage, toLocalPendingMessage, toOptimisticMessage } from "@/lib/messages";
import { cn } from "@/lib/utils";

export default function ChatArea() {
  const { state, fetchConversations, fetchGroupMembers, dispatch } = useStore();
  const [input, setInput] = useState("");
  const [uploading, setUploading] = useState(false);
  const [activeGroupCall, setActiveGroupCall] = useState<GroupCallSession | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const navigate = useNavigate();
  const { startCall, startGroupCall, joinGroupCall, endGroupCall } = useCall();

  const conv = state.conversations.find(
    (c) => c.conversationId === state.activeConversationId
  );
  const isSystemConversation = state.activeConversationId === SYSTEM_CONVERSATION_ID;
  const messages = conv ? state.messages[conv.conversationId] || [] : [];
  const groupMembers = conv?.groupId ? state.groupMembers[conv.groupId] || [] : [];
  const currentGroupMember = groupMembers.find((member) => member.userId === state.userId);
  const canEndActiveGroupCall = Boolean(
    activeGroupCall &&
      (activeGroupCall.initiatorUserId === state.userId ||
        groupMemberRoleRank(currentGroupMember?.roleLevel) >=
          groupMemberRoleRank(GroupMemberRole.ADMIN))
  );

  // Scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  // Load history when conversation changes
  useEffect(() => {
    if (isSystemConversation) return;
    if (!conv?.conversationId) return;
    const loadHistory = async () => {
      try {
        const maxSeq = await im.message.seq(conv.conversationId);
        if (maxSeq > 0) {
          const from = Math.max(0, maxSeq - 20);
          const msgs = await im.message.pull(conv.conversationId, from, maxSeq);
          if (msgs && msgs.length > 0) {
            const mapped = msgs.map((m) => ({
              messageId: m.messageId,
              seq: m.messageSeq,
              senderUserId: m.fromUserId,
              senderNickname: undefined as string | undefined,
              conversationId: m.conversationId,
              contentType: m.contentType,
              content: m.content,
              createTime: m.timestamp,
              status: m.status,
            }));
            dispatch({ type: "ADD_MESSAGES", conversationId: conv.conversationId, msgs: mapped });
          }
        }
      } catch {
        // History loading is best-effort; push sync will still converge later.
      }
    };
    loadHistory();
  }, [conv?.conversationId, conv?.latestMsg, conv?.latestMsgSendTime, dispatch, isSystemConversation, messages.length]);

  useEffect(() => {
    let cancelled = false;
    if (conv?.conversationType !== ConversationType.GROUP || !conv.groupId) {
      setActiveGroupCall(null);
      return;
    }
    const loadActiveGroupCall = async () => {
      try {
        await fetchGroupMembers(conv.groupId!);
        const active = await im.group.activeCall(conv.groupId!);
        if (!cancelled) setActiveGroupCall(active.active ? active : null);
      } catch {
        if (!cancelled) setActiveGroupCall(null);
      }
    };
    void loadActiveGroupCall();
    return () => {
      cancelled = true;
    };
  }, [conv?.conversationType, conv?.groupId, fetchGroupMembers]);

  const refreshActiveGroupCall = useCallback(async (groupId: string) => {
    try {
      const active = await im.group.activeCall(groupId);
      setActiveGroupCall(active.active ? active : null);
    } catch {
      setActiveGroupCall(null);
    }
  }, []);

  useEffect(() => {
    if (conv?.conversationType !== ConversationType.GROUP || !conv.groupId) return;
    const hasGroupSignal = messages.some((msg) => msg.contentType === MessageContentType.SIGNAL);
    if (!hasGroupSignal) return;
    let cancelled = false;
    const refresh = async () => {
      if (!cancelled) await refreshActiveGroupCall(conv.groupId!);
    };
    void refresh();
    return () => {
      cancelled = true;
    };
  }, [conv?.conversationType, conv?.groupId, messages, refreshActiveGroupCall]);

  const appendSentMessage = useCallback(
    (ack: SendMessageAck, contentType: OutgoingMessageContentTypeValue, content: unknown) => {
      if (!conv) return;
      const msg = toOptimisticMessage(ack, state.userId || "", contentType, content);
      dispatch({
        type: "UPSERT_SENT_MESSAGE",
        previousConversationId: conv.conversationId,
        conversation: { ...conv, conversationId: ack.conversationId },
        msg,
      });
      void fetchConversations();
    },
    [conv, dispatch, fetchConversations, state.userId]
  );

  const sendCurrentTextMessage = useCallback(
    async (content: string) => {
      if (!conv) return;
      const messageContent = { text: content };
      const clientMsgId = createClientMsgId();
      const pending = toLocalPendingMessage({
        conversationId: conv.conversationId,
        senderUserId: state.userId || "",
        contentType: "text",
        content: messageContent,
        messageId: clientMsgId,
      });
      dispatch({ type: "APPEND_MESSAGE", conversationId: conv.conversationId, msg: pending });
      try {
        await im.waitConnected();
        const ack =
          conv.conversationType === ConversationType.GROUP && conv.groupId
            ? await im.message.sendGroup({
                groupId: conv.groupId,
                contentType: "text",
                content: messageContent,
                clientMsgId,
              })
            : conv.userId
              ? await im.message.send({
                  toUserId: conv.userId,
                  contentType: "text",
                  content: messageContent,
                  clientMsgId,
                })
              : null;

        if (ack) {
          appendSentMessage(ack, "text", messageContent);
        }
      } catch (err) {
        const failed = toLocalFailedMessage(pending, getErrorText(err));
        dispatch({ type: "APPEND_MESSAGE", conversationId: conv.conversationId, msg: failed });
        throw err;
      }
    },
    [appendSentMessage, conv, dispatch, state.userId]
  );

  const handleSend = () => {
    if (!input.trim() || !conv || isSystemConversation) return;
    const content = input.trim();
    setInput("");
    void sendCurrentTextMessage(content).catch((err) => {
      console.error("send message failed:", err);
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const sendFileMessage = useCallback(
    async (file: File) => {
      if (!conv) return;
      setUploading(true);
      try {
        await im.waitConnected();
        const uploaded = await im.file.upload(file.name, file, file.type || "application/octet-stream");
        const fileContent = {
          uuid: uploaded.fileId,
          fileName: uploaded.fileName || file.name,
          fileSize: Number(uploaded.fileSize ?? file.size),
          url: uploaded.fileUrl,
        };

        const msg =
          conv.conversationType === ConversationType.GROUP && conv.groupId
            ? await im.message.sendGroup({
                groupId: conv.groupId,
                contentType: "file",
                content: fileContent,
                clientMsgId: createClientMsgId(),
              })
            : conv.userId
              ? await im.message.send({
                  toUserId: conv.userId,
                  contentType: "file",
                  content: fileContent,
                  clientMsgId: createClientMsgId(),
                })
              : null;

        if (msg) {
          appendSentMessage(msg, "file", fileContent);
          toast("文件已发送");
        }
      } catch (err) {
        console.error("send file failed:", err);
        toast(`文件发送失败：${getErrorText(err)}`);
      } finally {
        setUploading(false);
        if (fileInputRef.current) fileInputRef.current.value = "";
      }
    },
    [appendSentMessage, conv]
  );

  const handleRevoke = useCallback(
    async (msg: { conversationId: string; seq: number; groupId?: string }) => {
      try {
        await im.message.revoke({
          conversationId: msg.conversationId,
          messageSeq: msg.seq,
          ...(msg.groupId ? { groupId: msg.groupId } : {}),
        });
        dispatch({ type: "REVOKE_MESSAGE", conversationId: msg.conversationId, seq: msg.seq });
        toast("已撤回");
      } catch {
        toast("撤回失败");
      }
    },
    [dispatch]
  );

  const handleHeaderClick = () => {
    if (!conv || isSystemConversation) return;
    if (conv.conversationType === ConversationType.GROUP && conv.groupId) {
      navigate(`/chat/group/${conv.groupId}`);
    } else if (conv.userId) {
      navigate(`/chat/user/${conv.userId}`);
    }
  };

  const handleStartCall = useCallback(
    (callType: "voice" | "video") => {
      if (!conv?.userId || conv.conversationType === ConversationType.GROUP) return;
      void startCall({
        callType,
        peer: { userId: conv.userId, name: conv.showName, faceUrl: conv.faceUrl },
      });
    },
    [conv, startCall]
  );

  const handleStartGroupCall = useCallback(() => {
    if (!conv?.groupId || conv.conversationType !== ConversationType.GROUP) return;
    void startGroupCall({
      callType: "video",
      group: { groupId: conv.groupId, name: conv.showName, faceUrl: conv.faceUrl, canEnd: true },
    }).then(async () => {
      await refreshActiveGroupCall(conv.groupId!);
    });
  }, [conv, refreshActiveGroupCall, startGroupCall]);

  const handleJoinGroupCall = useCallback(() => {
    if (!conv?.groupId || conv.conversationType !== ConversationType.GROUP) return;
    void joinGroupCall({
      group: {
        groupId: conv.groupId,
        name: conv.showName,
        faceUrl: conv.faceUrl,
        canEnd: canEndActiveGroupCall,
      },
    }).then(async () => {
      await refreshActiveGroupCall(conv.groupId!);
    });
  }, [canEndActiveGroupCall, conv, joinGroupCall, refreshActiveGroupCall]);

  const handleEndGroupCall = useCallback(() => {
    if (!conv?.groupId || conv.conversationType !== ConversationType.GROUP) return;
    void endGroupCall().then(() => refreshActiveGroupCall(conv.groupId!));
  }, [conv, endGroupCall, refreshActiveGroupCall]);

  if (!state.activeConversationId) {
    return (
      <div className="flex h-full flex-1 flex-col items-center justify-center bg-[var(--app-bg)]">
        <div className="flex flex-col items-center gap-3 text-center">
          <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-white shadow-sm">
            <MessageCircle className="h-7 w-7 text-blue-500" />
          </div>
          <div>
            <p className="text-base font-semibold text-slate-700">选择一个会话开始</p>
            <p className="mt-1 max-w-xs text-sm text-slate-400">
              从左侧选择好友或群组，历史消息会显示在这里。
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (isSystemConversation) {
    return <SystemMessagePanel />;
  }

  return (
    <div className="flex h-full flex-1 flex-col bg-[var(--app-bg)]">
      {/* Header */}
      <PageHeader
        icon={
          <Avatar className="h-8 w-8 shadow-sm">
            <AvatarImage src={conv?.faceUrl} />
            <AvatarFallback className="bg-gradient-to-br from-slate-200 to-slate-300 text-xs text-slate-600">
              {(conv?.showName || "?").charAt(0).toUpperCase()}
            </AvatarFallback>
          </Avatar>
        }
        title={conv?.showName || "会话"}
        description={
          <span className="inline-flex items-center gap-1.5">
            <StatusDot tone={conv?.conversationType === ConversationType.GROUP ? "info" : "online"} />
            {conv?.conversationType === ConversationType.GROUP ? "群聊" : "单聊"}
          </span>
        }
        actions={
          <>
            {conv?.conversationType !== ConversationType.GROUP && conv?.userId && (
              <div className="flex items-center gap-0.5">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-9 w-9 rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-700"
                  title="语音通话"
                  onClick={() => handleStartCall("voice")}
                >
                  <Phone className="h-4 w-4" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-9 w-9 rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-700"
                  title="视频通话"
                  onClick={() => handleStartCall("video")}
                >
                  <Video className="h-4 w-4" />
                </Button>
              </div>
            )}
            {conv?.conversationType === ConversationType.GROUP && conv?.groupId && (
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-9 w-9 rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-700"
                title={activeGroupCall ? "加入群视频" : "发起群视频"}
                onClick={activeGroupCall ? handleJoinGroupCall : handleStartGroupCall}
              >
                <Video className="h-4 w-4" />
              </Button>
            )}
            <button
              type="button"
              onClick={handleHeaderClick}
              className="flex h-9 w-9 items-center justify-center rounded-lg text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700"
              title="查看资料"
            >
              <Info className="h-4 w-4" />
            </button>
          </>
        }
      />

      {/* Active group call banner */}
      {conv?.conversationType === ConversationType.GROUP && activeGroupCall && (
        <div className="border-b border-blue-100 bg-blue-50 px-5 py-2">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <div className="flex items-center gap-2 text-sm font-semibold text-blue-900">
                <StatusDot tone="online" pulse />
                群视频进行中
                <StateBadge tone="online">
                  {activeGroupCall.participantCount ?? activeGroupCall.participants?.length ?? 1} 人
                </StateBadge>
              </div>
              <div className="mt-0.5 truncate text-xs text-blue-600/80">
                发起人 {displayGroupCallUser(activeGroupCall.initiatorUserId, groupMembers)} ·{" "}
                {formatGroupCallStartedAt(activeGroupCall.startedAt)}
              </div>
            </div>
            <div className="flex items-center gap-2">
              {canEndActiveGroupCall && (
                <Button
                  size="sm"
                  variant="outline"
                  className="h-8 border-red-200 bg-white text-red-700 hover:bg-red-50"
                  onClick={handleEndGroupCall}
                >
                  <PhoneOff className="h-3.5 w-3.5" />
                  结束
                </Button>
              )}
              <Button
                size="sm"
                className="h-8 bg-blue-600 hover:bg-blue-700"
                onClick={handleJoinGroupCall}
              >
                加入
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Message list */}
      <ScrollArea className="flex-1 px-4 py-4 md:px-6">
        {messages.length === 0 && (
          <div className="flex h-full min-h-[320px] items-center justify-center">
            <EmptyState
              icon={<MessageCircle className="h-4 w-4" />}
              title="暂无消息"
              description="发送第一条消息开始聊天。"
            />
          </div>
        )}

        <div className="mx-auto max-w-3xl space-y-1 pb-2">
          {messages.map((msg) => {
            const isMine = msg.senderUserId === state.userId;
            const isRevoked = msg.contentType === 101 || msg.content === "消息已撤回";

            if (isRevoked) {
              return (
                <div key={msg.messageId} className="flex justify-center py-1">
                  <span className="rounded-full border border-slate-200 bg-white/70 px-4 py-1 text-xs text-slate-400 shadow-sm backdrop-blur-sm">
                    {isMine ? "你" : msg.senderNickname || msg.senderUserId} 撤回了一条消息
                  </span>
                </div>
              );
            }

            return (
              <div
                key={messageRenderKey(msg)}
                className={cn(
                  "msg-in flex items-end gap-2",
                  isMine ? "justify-end" : "justify-start"
                )}
              >
                {/* Other user avatar (left side) */}
                {!isMine && (
                  <Avatar
                    className="mb-1 h-8 w-8 shrink-0 cursor-pointer shadow-sm"
                    onClick={() => navigate(`/chat/user/${msg.senderUserId}`)}
                  >
                    <AvatarFallback className="bg-gradient-to-br from-slate-200 to-slate-300 text-[10px] text-slate-600">
                      {(msg.senderNickname || msg.senderUserId).charAt(0).toUpperCase()}
                    </AvatarFallback>
                  </Avatar>
                )}

                {/* Message group */}
                <div className={cn("group flex max-w-[72%] flex-col gap-1", isMine && "items-end")}>
                  {/* Sender name (in group chats, for others' messages) */}
                  {!isMine && conv?.conversationType === ConversationType.GROUP && (
                    <span className="pl-1 text-[11px] font-medium text-slate-500">
                      {msg.senderNickname || msg.senderUserId}
                    </span>
                  )}

                  <div className={cn("flex items-end gap-1.5", isMine && "flex-row-reverse")}>
                    {/* Bubble */}
                    <div
                      className={cn(
                        "relative px-3.5 py-2.5 text-sm shadow-sm",
                        msg.status === -1
                          ? "rounded-2xl border border-red-200 bg-red-50 text-red-800"
                          : isMine
                            ? "rounded-2xl rounded-br-md bg-gradient-to-br from-blue-500 to-blue-600 text-white"
                            : "rounded-2xl rounded-bl-md border border-slate-200/80 bg-white text-slate-800"
                      )}
                    >
                      <MessageContentRenderer message={msg} />
                      <div
                        className={cn(
                          "mt-1 text-[10px] leading-none",
                          isMine && msg.status !== -1
                            ? "text-white/60"
                            : "text-slate-400"
                        )}
                      >
                        {formatMsgTime(msg.createTime)}
                        {isMine &&
                          (msg.status === -1
                            ? " 发送失败"
                            : msg.status === 0
                              ? " 发送中…"
                              : "")}
                      </div>
                    </div>

                    {/* Status icon (failure / loading) */}
                    {isMine && (
                      <MessageStatusIcon status={msg.status} errorText={msg.errorText} />
                    )}

                    {/* Revoke dropdown — only for own messages */}
                    {isMine && (
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <button className="invisible mb-2 flex h-6 w-6 items-center justify-center rounded-md text-slate-400 opacity-0 transition-all hover:bg-slate-200 group-hover:visible group-hover:opacity-100">
                            <MoreHorizontal className="h-3.5 w-3.5" />
                          </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" side="top">
                          <DropdownMenuItem
                            onClick={() =>
                              handleRevoke({
                                conversationId: msg.conversationId,
                                seq: msg.seq,
                                groupId: conv?.groupId,
                              })
                            }
                          >
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
          <div ref={messagesEndRef} />
        </div>
      </ScrollArea>

      {/* Input bar */}
      <div className="border-t border-slate-200/80 bg-white px-4 py-3 md:px-5">
        <input
          ref={fileInputRef}
          type="file"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void sendFileMessage(file);
          }}
        />
        <div className="mx-auto flex max-w-3xl items-center gap-2">
          {/* Attachment */}
          <button
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600 disabled:cursor-not-allowed disabled:opacity-40"
            disabled={!conv || uploading}
            onClick={() => fileInputRef.current?.click()}
            title={uploading ? "正在上传文件" : "发送文件"}
          >
            <Paperclip className="h-4 w-4" />
          </button>

          {/* Input pill */}
          <div className="flex flex-1 items-center rounded-full border border-slate-200 bg-slate-50 px-4 transition-all focus-within:border-blue-300 focus-within:bg-white focus-within:shadow-sm">
            <input
              className="flex-1 bg-transparent py-2.5 text-sm text-slate-800 outline-none placeholder:text-slate-400"
              placeholder={isSystemConversation ? "系统通知不可回复" : "输入消息…"}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={isSystemConversation}
            />
          </div>

          {/* Send button */}
          <button
            onClick={handleSend}
            disabled={!input.trim() || isSystemConversation}
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-blue-600 text-white shadow-sm transition-all hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Send className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  );
}

function formatMsgTime(ts: number): string {
  if (!Number.isFinite(ts)) return "--:--";
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
}

function formatGroupCallStartedAt(ts?: number): string {
  if (!ts) return "刚刚开始";
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")} 开始`;
}

function displayGroupCallUser(
  userId: string | undefined,
  members: Array<{ userId: string; nickname?: string }>
): string {
  if (!userId) return "未知";
  const member = members.find((item) => item.userId === userId);
  return member?.nickname || userId;
}

import { useState, useEffect, useCallback, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { SYSTEM_CONVERSATION_ID, useStore } from "@/store/store";
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
import { MessageCircle, Send, Paperclip, MoreHorizontal, Undo2, Info, Phone, Video } from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { MessageContentRenderer } from "@/components/MessageContentRenderer";
import SystemMessagePanel from "@/components/SystemMessagePanel";
import { ConversationType, MessageContentType, createClientMsgId, getErrorText, toMessageContentType, type GroupCallSession, type OutgoingMessageContentTypeValue, type SendMessageAck } from "im-sdk";
import { useCall } from "@/components/call/CallProvider";

export default function ChatArea() {
  const { state, sendMessage, fetchConversations, dispatch } = useStore();
  const [input, setInput] = useState("");
  const [uploading, setUploading] = useState(false);
  const [activeGroupCall, setActiveGroupCall] = useState<GroupCallSession | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const navigate = useNavigate();
  const { startCall, startGroupCall, joinGroupCall } = useCall();

  const conv = state.conversations.find(
    (c) => c.conversationId === state.activeConversationId
  );
  const isSystemConversation = state.activeConversationId === SYSTEM_CONVERSATION_ID;
  const messages = conv ? state.messages[conv.conversationId] || [] : [];

  // Load history when conversation changes
  useEffect(() => {
    if (isSystemConversation) return;
    if (!conv?.conversationId) return;
    if (!conv.latestMsg && !conv.latestMsgSendTime && messages.length === 0) return;
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
        // silent — history loading is best-effort
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
  }, [conv?.conversationType, conv?.groupId]);

  useEffect(() => {
    if (conv?.conversationType !== ConversationType.GROUP || !conv.groupId) return;
    const hasGroupSignal = messages.some((msg) => msg.contentType === MessageContentType.SIGNAL);
    if (!hasGroupSignal) return;
    let cancelled = false;
    const refresh = async () => {
      try {
        const active = await im.group.activeCall(conv.groupId!);
        if (!cancelled) setActiveGroupCall(active.active ? active : null);
      } catch {
        if (!cancelled) setActiveGroupCall(null);
      }
    };
    void refresh();
    return () => {
      cancelled = true;
    };
  }, [conv?.conversationType, conv?.groupId, messages]);

  const handleSend = () => {
    if (!input.trim() || !conv || isSystemConversation) return;
    const content = input.trim();
    setInput("");

    if (conv.conversationType === ConversationType.GROUP && conv.groupId) {
      // Group chat
      const groupId = conv.groupId;
      const clientMsgId = createClientMsgId();
      im.waitConnected()
        .then(() => im.message.sendGroup({
          groupId,
          contentType: "text",
          content: { text: content },
          clientMsgId,
        }))
        .then((m) => {
          appendSentMessage(m, "text", { text: content });
        })
        .catch((err) => {
          toast(`发送失败：${getErrorText(err)}`);
          setInput(content);
        });
    } else if (conv.userId) {
      // Single chat
      sendMessage(conv.userId, content).catch((err) => {
        toast(`发送失败：${getErrorText(err)}`);
        setInput(content);
      });
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const appendSentMessage = useCallback((
    ack: SendMessageAck,
    contentType: OutgoingMessageContentTypeValue,
    content: unknown,
  ) => {
    const createTime = Date.now();
    dispatch({
      type: "APPEND_MESSAGE",
      conversationId: ack.conversationId,
        msg: {
        messageId: ack.messageId,
        seq: ack.seq ?? 0,
        senderUserId: state.userId || "",
        conversationId: ack.conversationId,
        contentType: toMessageContentType(contentType),
        content: toOutgoingMessageContent(content),
        createTime,
        status: 1,
      },
    });
    dispatch({
      type: "UPDATE_CONVERSATION_LATEST",
      conversationId: ack.conversationId,
      latestMsg: toOutgoingMessageContent(content),
      latestMsgSendTime: createTime,
    });
    void fetchConversations();
  }, [dispatch, fetchConversations, state.userId]);

  const sendFileMessage = useCallback(async (file: File) => {
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

      const msg = conv.conversationType === ConversationType.GROUP && conv.groupId
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
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    }
  }, [appendSentMessage, conv]);

  const handleRevoke = useCallback(async (msg: { conversationId: string; seq: number; groupId?: string }) => {
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
  }, [dispatch]);

  const handleHeaderClick = () => {
    if (!conv || isSystemConversation) return;
    if (conv.conversationType === ConversationType.GROUP && conv.groupId) {
      navigate(`/chat/group/${conv.groupId}`);
    } else if (conv.userId) {
      navigate(`/chat/user/${conv.userId}`);
    }
  };

  const handleStartCall = useCallback((callType: "voice" | "video") => {
    if (!conv?.userId || conv.conversationType === ConversationType.GROUP) return;
    void startCall({
      callType,
      peer: {
        userId: conv.userId,
        name: conv.showName,
        faceUrl: conv.faceUrl,
      },
    });
  }, [conv, startCall]);

  const handleStartGroupCall = useCallback(() => {
    if (!conv?.groupId || conv.conversationType !== ConversationType.GROUP) return;
    void startGroupCall({
      callType: "video",
      group: {
        groupId: conv.groupId,
        name: conv.showName,
        faceUrl: conv.faceUrl,
      },
    }).then(async () => {
      const active = await im.group.activeCall(conv.groupId!);
      setActiveGroupCall(active.active ? active : null);
    });
  }, [conv, startGroupCall]);

  const handleJoinGroupCall = useCallback(() => {
    if (!conv?.groupId || conv.conversationType !== ConversationType.GROUP) return;
    void joinGroupCall({
      group: {
        groupId: conv.groupId,
        name: conv.showName,
        faceUrl: conv.faceUrl,
      },
    }).then(async () => {
      const active = await im.group.activeCall(conv.groupId!);
      setActiveGroupCall(active.active ? active : null);
    });
  }, [conv, joinGroupCall]);

  // Empty state
  if (!state.activeConversationId) {
    return (
      <div className="flex h-full flex-1 items-center justify-center bg-slate-50">
        <div className="max-w-sm px-6 text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-md bg-white text-slate-500 shadow-sm ring-1 ring-slate-200">
            <MessageCircle className="h-5 w-5" />
          </div>
          <h2 className="text-base font-semibold text-slate-700">
            选择一个会话开始聊天
          </h2>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">
            从左侧选择好友或群组
          </p>
        </div>
      </div>
    );
  }

  if (isSystemConversation) {
    return <SystemMessagePanel />;
  }

  return (
    <div className="flex h-full flex-1 flex-col bg-slate-50">
      <div className="flex items-center gap-3 border-b border-slate-200 bg-white/95 px-5 py-3 shadow-sm">
        <button
          onClick={handleHeaderClick}
          className="flex min-w-0 flex-1 items-center gap-3 rounded-md text-left transition-colors hover:text-slate-700"
        >
          <Avatar className="h-10 w-10 border border-white shadow-sm">
            <AvatarImage src={conv?.faceUrl} />
            <AvatarFallback className="bg-slate-100 text-slate-700">
              {(conv?.showName || "?").charAt(0).toUpperCase()}
            </AvatarFallback>
          </Avatar>
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-semibold">{conv?.showName}</div>
            <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
              {conv?.conversationType === ConversationType.GROUP ? "群聊" : "单聊"}
            </div>
          </div>
        </button>
        {conv?.conversationType !== ConversationType.GROUP && conv?.userId && (
          <div className="flex items-center gap-1">
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-9 w-9 rounded-md"
              title="语音通话"
              onClick={() => handleStartCall("voice")}
            >
              <Phone className="h-4 w-4" />
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-9 w-9 rounded-md"
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
            className="h-9 w-9 rounded-md"
            title={activeGroupCall ? "加入群视频" : "发起群视频"}
            onClick={activeGroupCall ? handleJoinGroupCall : handleStartGroupCall}
          >
            <Video className="h-4 w-4" />
          </Button>
        )}
        <button
          type="button"
          onClick={handleHeaderClick}
          className="flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-slate-100 hover:text-foreground"
          title="查看资料"
        >
          <Info className="h-4 w-4" />
        </button>
      </div>

      {conv?.conversationType === ConversationType.GROUP && activeGroupCall && (
        <div className="border-b bg-emerald-50 px-4 py-2 text-sm text-emerald-900">
          <div className="flex items-center justify-between gap-3">
            <span>
              群视频进行中，{activeGroupCall.participantCount ?? 1} 人正在通话
            </span>
            <Button size="sm" className="h-7 bg-emerald-600 hover:bg-emerald-700" onClick={handleJoinGroupCall}>
              加入
            </Button>
          </div>
        </div>
      )}

      {/* Messages */}
      <ScrollArea className="flex-1 px-5 py-4">
        {messages.length === 0 && (
          <div className="flex h-full min-h-[360px] items-center justify-center">
            <div className="rounded-md border border-dashed bg-white/70 px-5 py-4 text-center text-sm text-muted-foreground">
              暂无消息，发送第一条消息吧
            </div>
          </div>
        )}

        <div className="mx-auto max-w-4xl space-y-3">
          {messages.map((msg) => {
            const isMine = msg.senderUserId === state.userId;
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
                key={messageRenderKey(msg)}
                className={`flex ${isMine ? "justify-end" : "justify-start"}`}
              >
                <div className="group flex max-w-[78%] flex-col">
                  <div className="flex items-end gap-2">
                    {!isMine && (
                      <Avatar className="mb-1 h-7 w-7 cursor-pointer border border-white shadow-sm"
                        onClick={() => navigate(`/chat/user/${msg.senderUserId}`)}>
                        <AvatarFallback className="text-[10px]">
                          {(msg.senderNickname || msg.senderUserId).charAt(0).toUpperCase()}
                        </AvatarFallback>
                      </Avatar>
                    )}
                    <div
                      className={`rounded-md px-3 py-2 text-sm shadow-sm ${
                        isMine
                          ? "bg-slate-900 text-white"
                          : "border border-slate-200 bg-white text-slate-900"
                      }`}
                    >
                      {!isMine && (
                        <div className="mb-1 text-xs opacity-70">
                          {msg.senderNickname || msg.senderUserId}
                        </div>
                      )}
                      <MessageContentRenderer message={msg} />
                      <div
                        className={`mt-1 text-[10px] ${
                          isMine ? "text-white/60" : "text-muted-foreground"
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
                          <button className="invisible rounded-md p-1 text-muted-foreground opacity-0 transition-all hover:bg-white group-hover:visible group-hover:opacity-100">
                            <MoreHorizontal className="h-3.5 w-3.5" />
                          </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" side="top">
                          <DropdownMenuItem onClick={() => handleRevoke({
                            conversationId: msg.conversationId,
                            seq: msg.seq,
                            groupId: conv?.groupId,
                          })}>
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
      <div className="border-t border-slate-200 bg-white/95 px-5 py-3">
        <div className="mx-auto flex max-w-4xl items-center gap-2">
          <input
            ref={fileInputRef}
            type="file"
            className="hidden"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void sendFileMessage(file);
            }}
          />
          <button
            className="flex h-10 w-10 items-center justify-center rounded-md text-muted-foreground hover:bg-slate-100 hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
            disabled={!conv || uploading}
            onClick={() => fileInputRef.current?.click()}
            title={uploading ? "正在上传文件" : "发送文件"}
          >
            <Paperclip className="h-4 w-4 text-muted-foreground" />
          </button>
          <Input
            placeholder="输入消息..."
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            className="h-10 flex-1 border-slate-200 bg-slate-50"
          />
          <Button size="icon" className="h-10 w-10 rounded-md bg-slate-900 hover:bg-slate-800" onClick={handleSend} disabled={!input.trim()}>
            <Send className="h-4 w-4" />
          </Button>
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

function toOutgoingMessageContent(raw: unknown): string {
  return typeof raw === "string" ? raw : JSON.stringify(raw);
}

function messageRenderKey(msg: { messageId?: string; seq?: number; senderUserId?: string; createTime?: number; content?: string }): string {
  if (msg.messageId) return msg.messageId;
  if (msg.seq && msg.seq > 0) return `seq:${msg.seq}`;
  return `tmp:${msg.senderUserId || "unknown"}:${msg.createTime || 0}:${msg.content || ""}`;
}

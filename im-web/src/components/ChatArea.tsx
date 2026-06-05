import { useState, useEffect, useCallback, useRef } from "react";
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
import { Send, Paperclip, MoreHorizontal, Undo2, Info, Phone, Video } from "lucide-react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { MessageContentRenderer } from "@/components/MessageContentRenderer";
import { ConversationType, toMessageContentType, type OutgoingMessageContentTypeValue, type SendMessageAck } from "im-sdk";
import { useCall } from "@/components/call/CallProvider";

export default function ChatArea() {
  const { state, sendMessage, fetchConversations, dispatch } = useStore();
  const [input, setInput] = useState("");
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const navigate = useNavigate();
  const { startCall } = useCall();

  const conv = state.conversations.find(
    (c) => c.conversationId === state.activeConversationId
  );
  const messages = conv ? state.messages[conv.conversationId] || [] : [];

  // Load history when conversation changes
  useEffect(() => {
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
  }, [conv?.conversationId, conv?.latestMsg, conv?.latestMsgSendTime, dispatch, messages.length]);

  const handleSend = () => {
    if (!input.trim() || !conv) return;
    const content = input.trim();
    setInput("");

    if (conv.conversationType === ConversationType.GROUP && conv.groupId) {
      // Group chat
      im.message
        .sendGroup(conv.groupId, "text", { text: content })
        .then((m) => {
          appendSentMessage(m, "text", { text: content });
        })
        .catch(() => {
          toast("发送失败");
          setInput(content);
        });
    } else if (conv.userId) {
      // Single chat
      sendMessage(conv.userId, content).catch(() => {
        toast("发送失败");
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
        messageId: "",
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
      const bytes = new Uint8Array(await file.arrayBuffer());
      const uploaded = await im.file.upload(file.name, bytes, file.type || "application/octet-stream");
      const fileContent = {
        uuid: uploaded.fileId,
        fileName: uploaded.fileName || file.name,
        fileSize: Number(uploaded.fileSize ?? file.size),
        url: uploaded.fileUrl,
      };

      const msg = conv.conversationType === ConversationType.GROUP && conv.groupId
        ? await im.message.sendGroup(conv.groupId, "file", fileContent)
        : conv.userId
          ? await im.message.send({ toUserId: conv.userId, contentType: "file", content: fileContent })
          : null;

      if (msg) {
        appendSentMessage(msg, "file", fileContent);
        toast("文件已发送");
      }
    } catch (err) {
      console.error("send file failed:", err);
      toast(`文件发送失败：${errorMessage(err)}`);
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
    if (!conv) return;
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
      <div className="flex items-center gap-3 border-b px-4 py-3">
      <button
        onClick={handleHeaderClick}
        className="flex flex-1 items-center gap-3 text-left transition-colors"
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
              title="语音通话"
              onClick={() => handleStartCall("voice")}
            >
              <Phone className="h-4 w-4" />
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              title="视频通话"
              onClick={() => handleStartCall("video")}
            >
              <Video className="h-4 w-4" />
            </Button>
          </div>
        )}
        <button
          type="button"
          onClick={handleHeaderClick}
          className="rounded-md p-2 text-muted-foreground transition-colors hover:bg-accent"
          title="查看资料"
        >
          <Info className="h-4 w-4" />
        </button>
      </div>

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
                <div className="group flex max-w-[70%] flex-col">
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
                      <MessageContentRenderer message={msg} />
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
      <div className="border-t p-3">
        <div className="flex items-center gap-2">
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
            className="rounded-md p-2 hover:bg-accent disabled:cursor-not-allowed disabled:opacity-50"
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

function errorMessage(err: unknown): string {
  if (err instanceof Error && err.message) return err.message;
  if (typeof err === "object" && err !== null && "message" in err) {
    return String((err as { message?: unknown }).message || "未知错误");
  }
  return "未知错误";
}

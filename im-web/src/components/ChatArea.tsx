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
  const { state, sendMessage, dispatch } = useStore();
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
  }, [conv?.conversationId, dispatch]);

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

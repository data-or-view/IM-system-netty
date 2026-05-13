import { useState } from "react";
import { useStore } from "@/store/store";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Send, Paperclip } from "lucide-react";

export default function ChatArea() {
  const { state, sendMessage } = useStore();
  const [input, setInput] = useState("");

  const conv = state.conversations.find(
    (c) => c.conversationId === state.activeConversationId
  );

  const messages = conv ? state.messages[conv.conversationId] || [] : [];

  const handleSend = () => {
    if (!input.trim() || !conv) return;

    if (conv.userId) {
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

  // 空状态：未选择会话
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
      {/* Chat Header */}
      <div className="flex items-center gap-3 border-b px-4 py-3">
        <Avatar className="h-9 w-9">
          <AvatarImage src={conv?.faceUrl} />
          <AvatarFallback>
            {(conv?.showName || "?").charAt(0).toUpperCase()}
          </AvatarFallback>
        </Avatar>
        <div>
          <div className="text-sm font-medium">{conv?.showName}</div>
          <div className="text-xs text-muted-foreground">
            {conv?.conversationType === 2 ? "群聊" : "单聊"}
          </div>
        </div>
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
            return (
              <div
                key={msg.messageId}
                className={`flex ${isMine ? "justify-end" : "justify-start"}`}
              >
                <div
                  className={`max-w-[70%] rounded-lg px-3 py-2 text-sm ${
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
          <Button
            size="icon"
            onClick={handleSend}
            disabled={!input.trim()}
          >
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

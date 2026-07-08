import { useEffect, useRef } from "react";
import { toast } from "sonner";
import { getErrorText } from "im-sdk";
import { im } from "@/sdk/im-sdk";
import { APP_BEHAVIOR } from "@/config/app-behavior";
import { createLogger } from "@/lib/logger";
import { normalizeMessageStatus } from "@/lib/messages";
import type { Conversation, Message } from "@/store/store";

const log = createLogger("ui.conversation-history");

type AddMessagesAction = {
  type: "ADD_MESSAGES";
  conversationId: string;
  msgs: Message[];
};

interface UseConversationHistoryOptions {
  conversation: Conversation | undefined;
  isSystemConversation: boolean;
  messageCount: number;
  dispatch: (action: AddMessagesAction) => void;
}

export function useConversationHistory({
  conversation,
  isSystemConversation,
  messageCount,
  dispatch,
}: UseConversationHistoryOptions) {
  const historyErrorNotifiedRef = useRef<string | null>(null);

  useEffect(() => {
    if (isSystemConversation) return;
    if (!conversation?.conversationId) return;

    const loadHistory = async () => {
      try {
        const maxSeq = await im.message.seq(conversation.conversationId);
        if (maxSeq <= 0) return;

        const from = Math.max(0, maxSeq - APP_BEHAVIOR.messages.historyPageSize);
        const msgs = await im.message.pull(conversation.conversationId, from, maxSeq);
        if (!msgs || msgs.length === 0) return;

        dispatch({
          type: "ADD_MESSAGES",
          conversationId: conversation.conversationId,
          msgs: msgs.map((message) => ({
            messageId: message.messageId,
            seq: message.messageSeq,
            senderUserId: message.fromUserId,
            senderNickname: undefined,
            conversationId: message.conversationId,
            contentType: message.contentType,
            content: message.content,
            createTime: message.timestamp,
            status: normalizeMessageStatus(message.status, message.messageSeq),
          })),
        });
      } catch (err) {
        log.warn("load history failed", { conversationId: conversation.conversationId, error: err });
        if (historyErrorNotifiedRef.current !== conversation.conversationId) {
          historyErrorNotifiedRef.current = conversation.conversationId;
          toast(`历史消息加载失败：${getErrorText(err)}`);
        }
      }
    };

    void loadHistory();
  }, [
    conversation?.conversationId,
    conversation?.latestMsg,
    conversation?.latestMsgSendTime,
    dispatch,
    isSystemConversation,
    messageCount,
  ]);
}

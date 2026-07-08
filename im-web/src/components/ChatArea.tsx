import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { MessageCircle } from "lucide-react";
import { toast } from "sonner";
import {
  ConversationType,
  OutgoingMessageContentType,
  createClientMsgId,
  getErrorText,
  type OutgoingMessageContentTypeValue,
  type SendMessageAck,
} from "im-sdk";
import { SYSTEM_CONVERSATION_ID, useStore } from "@/store/store";
import SystemMessagePanel from "@/components/SystemMessagePanel";
import ChatHeader from "@/components/chat/ChatHeader";
import GroupCallBanner from "@/components/chat/GroupCallBanner";
import MessageComposer from "@/components/chat/MessageComposer";
import MessageList from "@/components/chat/MessageList";
import { useActiveGroupCall } from "@/components/chat/useActiveGroupCall";
import { useConversationHistory } from "@/components/chat/useConversationHistory";
import { im } from "@/sdk/im-sdk";
import { APP_ROUTES } from "@/config/routes";
import { createLogger } from "@/lib/logger";
import {
  toLocalFailedMessage,
  toLocalPendingMessage,
  toOptimisticMessage,
} from "@/lib/messages";

const log = createLogger("ui.chat-area");

export default function ChatArea() {
  const { state, fetchConversations, fetchGroupMembers, dispatch } = useStore();
  const [input, setInput] = useState("");
  const [uploading, setUploading] = useState(false);
  const navigate = useNavigate();

  const conversation = state.conversations.find(
    (item) => item.conversationId === state.activeConversationId
  );
  const isSystemConversation = state.activeConversationId === SYSTEM_CONVERSATION_ID;
  const messages = conversation ? state.messages[conversation.conversationId] || [] : [];
  const groupMembers = conversation?.groupId ? state.groupMembers[conversation.groupId] || [] : [];

  useConversationHistory({
    conversation,
    isSystemConversation,
    messageCount: messages.length,
    dispatch,
  });

  const {
    activeGroupCall,
    groupCallBusy,
    canEndActiveGroupCall,
    handleStartCall,
    handleStartGroupCall,
    handleJoinGroupCall,
    handleEndGroupCall,
  } = useActiveGroupCall({
    conversation,
    messages,
    groupMembers,
    currentUserId: state.userId,
    fetchGroupMembers,
  });

  const appendSentMessage = useCallback(
    (ack: SendMessageAck, contentType: OutgoingMessageContentTypeValue, content: unknown) => {
      if (!conversation) return;
      const msg = toOptimisticMessage(ack, state.userId || "", contentType, content);
      dispatch({
        type: "UPSERT_SENT_MESSAGE",
        previousConversationId: conversation.conversationId,
        conversation: { ...conversation, conversationId: ack.conversationId },
        msg,
      });
      void fetchConversations();
    },
    [conversation, dispatch, fetchConversations, state.userId]
  );

  const sendCurrentTextMessage = useCallback(
    async (content: string) => {
      if (!conversation) return;
      const messageContent = { text: content };
      const clientMsgId = createClientMsgId();
      const pending = toLocalPendingMessage({
        conversationId: conversation.conversationId,
        senderUserId: state.userId || "",
        contentType: OutgoingMessageContentType.TEXT,
        content: messageContent,
        messageId: clientMsgId,
      });
      dispatch({ type: "APPEND_MESSAGE", conversationId: conversation.conversationId, msg: pending });
      try {
        await im.waitConnected();
        const ack =
          conversation.conversationType === ConversationType.GROUP && conversation.groupId
            ? await im.message.sendGroup({
                groupId: conversation.groupId,
                contentType: OutgoingMessageContentType.TEXT,
                content: messageContent,
                clientMsgId,
              })
            : conversation.userId
              ? await im.message.send({
                  toUserId: conversation.userId,
                  contentType: OutgoingMessageContentType.TEXT,
                  content: messageContent,
                  clientMsgId,
                })
              : null;

        if (ack) {
          appendSentMessage(ack, OutgoingMessageContentType.TEXT, messageContent);
        }
      } catch (err) {
        const failed = toLocalFailedMessage(pending, getErrorText(err));
        dispatch({ type: "APPEND_MESSAGE", conversationId: conversation.conversationId, msg: failed });
        throw err;
      }
    },
    [appendSentMessage, conversation, dispatch, state.userId]
  );

  const handleSend = () => {
    if (!input.trim() || !conversation || isSystemConversation) return;
    const content = input.trim();
    setInput("");
    void sendCurrentTextMessage(content).catch((err) => {
      log.error("send message failed", { conversationId: conversation.conversationId, error: err });
    });
  };

  const sendFileMessage = useCallback(
    async (file: File) => {
      if (!conversation) return;
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
          conversation.conversationType === ConversationType.GROUP && conversation.groupId
            ? await im.message.sendGroup({
                groupId: conversation.groupId,
                contentType: "file",
                content: fileContent,
                clientMsgId: createClientMsgId(),
              })
            : conversation.userId
              ? await im.message.send({
                  toUserId: conversation.userId,
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
        log.error("send file failed", { conversationId: conversation.conversationId, fileName: file.name, error: err });
        toast(`文件发送失败：${getErrorText(err)}`);
      } finally {
        setUploading(false);
      }
    },
    [appendSentMessage, conversation]
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
      } catch (err) {
        toast(`撤回失败：${getErrorText(err)}`);
      }
    },
    [dispatch]
  );

  const handleHeaderClick = () => {
    if (!conversation || isSystemConversation) return;
    if (conversation.conversationType === ConversationType.GROUP && conversation.groupId) {
      navigate(APP_ROUTES.group(conversation.groupId));
    } else if (conversation.userId) {
      navigate(APP_ROUTES.user(conversation.userId));
    }
  };

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
      <ChatHeader
        conversation={conversation}
        activeGroupCall={activeGroupCall}
        groupCallBusy={groupCallBusy}
        onShowInfo={handleHeaderClick}
        onStartCall={handleStartCall}
        onStartGroupCall={handleStartGroupCall}
        onJoinGroupCall={handleJoinGroupCall}
      />
      {conversation?.conversationType === ConversationType.GROUP && (
        <GroupCallBanner
          activeGroupCall={activeGroupCall}
          groupMembers={groupMembers}
          canEndActiveGroupCall={canEndActiveGroupCall}
          groupCallBusy={groupCallBusy}
          onEndGroupCall={handleEndGroupCall}
          onJoinGroupCall={handleJoinGroupCall}
        />
      )}
      <MessageList
        conversation={conversation}
        messages={messages}
        currentUserId={state.userId}
        onRevoke={handleRevoke}
      />
      <MessageComposer
        value={input}
        uploading={uploading}
        disabled={isSystemConversation}
        canAttach={Boolean(conversation)}
        onChange={setInput}
        onSend={handleSend}
        onFileSelected={(file) => void sendFileMessage(file)}
      />
    </div>
  );
}

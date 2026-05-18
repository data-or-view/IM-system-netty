/**
 * React Hooks 适配层。
 *
 * 将 IM SDK 的事件驱动模型转为 React Hook，方便组件消费。
 * 所有状态变更通过 React state 驱动重渲染。
 *
 * @example
 * ```tsx
 * function ChatView() {
 *   const { messages, sendMessage, connected } = useIM();
 *   return <div>{messages.map(m => <p>{m.content}</p>)}</div>;
 * }
 * ```
 */

import { useState, useEffect, useCallback, useRef } from "react";
import { im } from "./im-sdk";
import type { ConnectionState, Message, FriendApply, IMError } from "im-sdk";

export interface IMState {
  connected: boolean;
  connecting: boolean;
  state: ConnectionState;
  messages: Message[];
  friendRequests: FriendApply[];
  error: IMError | null;
}

export function useIM() {
  const [state, setState] = useState<IMState>({
    connected: false,
    connecting: false,
    state: "disconnected",
    messages: [],
    friendRequests: [],
    error: null,
  });

  const messagesRef = useRef<Message[]>([]);
  const friendRequestsRef = useRef<FriendApply[]>([]);

  useEffect(() => {
    const unsub1 = im.on("connectionStateChanged", (s) => {
      setState((prev) => ({
        ...prev,
        state: s,
        connected: s === "connected",
        connecting: s === "connecting" || s === "reconnecting",
      }));
    });

    const unsub2 = im.on("message", (msg) => {
      messagesRef.current = [...messagesRef.current, msg];
      setState((prev) => ({ ...prev, messages: messagesRef.current }));
    });

    const unsub3 = im.on("friendRequest", (req) => {
      friendRequestsRef.current = [...friendRequestsRef.current, req];
      setState((prev) => ({ ...prev, friendRequests: friendRequestsRef.current }));
    });

    const unsub4 = im.on("error", (err) => {
      setState((prev) => ({ ...prev, error: err }));
    });

    return () => {
      unsub1();
      unsub2();
      unsub3();
      unsub4();
    };
  }, []);

  const connect = useCallback(() => im.connect(), []);
  const disconnect = useCallback(() => im.disconnect(), []);

  return {
    ...state,
    connect,
    disconnect,
    user: im.user,
    friend: im.friend,
    group: im.group,
    message: im.message,
    conversation: im.conversation,
    file: im.file,
  };
}

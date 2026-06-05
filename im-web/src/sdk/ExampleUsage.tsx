/**
 * SDK 使用示例。
 *
 * 展示如何在 React 组件中使用 IM SDK。
 * 这不是一个真实页面，只是 API 用法的参考。
 */
import { useEffect } from "react";
import { im } from "./im-sdk";
import { useIM } from "./useIM";

// ── 1. 直接使用 SDK（非 React 场景） ──
export async function sdkExample() {
  im.connect();

  // 等待连接建立后操作
  setTimeout(async () => {
    // 登录（自动关联 seq → ack）
    const loginResp = await im.user.login("user_001");
    const token = loginResp.data as string;
    localStorage.setItem("im_token", token);

    // 搜索用户
    const users = await im.friend.search("alice");
    console.log("Users:", users);

    // 申请加好友
    await im.friend.apply("user_002", "你好");

    // 发消息
    const msg = await im.message.send({
      toUserId: "user_002",
      contentType: "text",
      content: { text: "Hello!" },
    });
    console.log("Sent:", msg);

    // 拉取会话列表
    const convs = await im.conversation.list();
    console.log("Conversations:", convs);
  }, 1000);
}

// ── 2. React Hooks 使用 ──
export function ChatPage() {
  const { messages, connected, connect } = useIM();

  // 连接
  useEffect(() => {
    if (!connected) connect();
  }, []);

  return (
    <div>
      <p>状态: {connected ? "已连接" : "未连接"}</p>
      {messages.map((m) => (
        <p key={m.messageId}>{m.fromUserId}: {m.content}</p>
      ))}
    </div>
  );
}

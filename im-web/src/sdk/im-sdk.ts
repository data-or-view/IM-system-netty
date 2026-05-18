/**
 * IM SDK 集成 —— 创建并导出 SDK 单例供 im-web 使用。
 *
 * 用法：
 * ```tsx
 * import { im } from "@/sdk/im-sdk";
 * import { useEffect } from "react";
 *
 * function App() {
 *   useEffect(() => {
 *     const unsub = im.on("message", (msg) => {
 *       console.log("新消息:", msg);
 *     });
 *     return unsub;
 *   }, []);
 *
 *   const handleLogin = async () => {
 *     await im.user.login("user_001");
 *     const conversations = await im.conversation.list();
 *     setConvs(conversations);
 *   };
 *
 *   return <button onClick={handleLogin}>登录</button>;
 * }
 * ```
 */

import { createIM } from "im-sdk";

// WS 连接地址优先级: VITE_WS_URL 环境变量 → 同源代理
// 开发环境走 Vite proxy (vite.config.ts 将 /ws 转发到后端 Node 1:8081)
const WS_URL =
  import.meta.env.VITE_WS_URL ?? `ws://${location.host}/ws`;

export const im = createIM({
  wsUrl: WS_URL,
  getToken: () => localStorage.getItem("im_token"),
});

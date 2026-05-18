import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
      // SDK 源码直接引用，支持热更新
      "im-sdk": path.resolve(__dirname, "../im-sdk/src"),
    },
  },
  server: {
    port: 8083,
    host: "0.0.0.0",
    // 将 /ws 转发到后端 Node 1（端口 8081）
    proxy: {
      "/ws": {
        target: "ws://127.0.0.1:8081",
        ws: true,
      },
    },
  },
});

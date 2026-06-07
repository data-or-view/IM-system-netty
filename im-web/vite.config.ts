import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

declare const process: { cwd: () => string };

const root = process.cwd();

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": `${root}/src`,
      // SDK 源码直接引用，支持热更新。
      "im-sdk": `${root}/../im-sdk/src`,
    },
  },
  server: {
    port: 39073,
    strictPort: true,
    host: "0.0.0.0",
    proxy: {
      "/ws": {
        target: "ws://127.0.0.1:8083",
        ws: true,
      },
      "/api": {
        target: "http://127.0.0.1:8084",
        changeOrigin: true,
      },
    },
  },
});

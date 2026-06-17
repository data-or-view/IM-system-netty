import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

declare const process: { cwd: () => string };

const root = process.cwd();
const DEV_WEB_PORT = 39073;
const DEV_WS_TARGET = "ws://127.0.0.1:8084";
const DEV_HTTP_TARGET = "http://127.0.0.1:8089";

export default defineConfig({
  plugins: [react()],
  build: {
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("react") || id.includes("react-dom") || id.includes("react-router-dom")) {
            return "vendor-react";
          }
          if (id.includes("@radix-ui")) {
            return "vendor-radix";
          }
          if (id.includes("livekit-client")) {
            return "vendor-livekit";
          }
          if (id.includes("/im-sdk/src/")) {
            return "vendor-im-sdk";
          }
          if (id.includes("lucide-react") || id.includes("sonner") || id.includes("date-fns")) {
            return "vendor-ui-utils";
          }
          return undefined;
        },
      },
    },
  },
  resolve: {
    alias: {
      "@": `${root}/src`,
      // SDK 源码直接引用，支持热更新。
      "im-sdk": `${root}/../im-sdk/src`,
    },
  },
  server: {
    port: DEV_WEB_PORT,
    strictPort: true,
    host: "0.0.0.0",
    proxy: {
      "/ws": {
        target: DEV_WS_TARGET,
        ws: true,
      },
      "/api": {
        target: DEV_HTTP_TARGET,
        changeOrigin: true,
      },
    },
  },
});

import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { DEV_HTTP_URL, DEV_WEB_PORT, DEV_WS_URL } from "./src/config/runtime";

declare const process: { cwd: () => string; env: Record<string, string | undefined> };

const root = process.cwd();
const devWebPort = numberEnv("VITE_DEV_WEB_PORT", DEV_WEB_PORT);
const devWsTarget = process.env.VITE_WS_PROXY_TARGET ?? originOf(DEV_WS_URL);
const devHttpTarget = process.env.VITE_HTTP_PROXY_TARGET ?? DEV_HTTP_URL;

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
    port: devWebPort,
    strictPort: true,
    host: "0.0.0.0",
    proxy: {
      "/ws": {
        target: devWsTarget,
        ws: true,
      },
      "/api": {
        target: devHttpTarget,
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: devWebPort,
    strictPort: true,
    host: "0.0.0.0",
  },
});

function numberEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function originOf(value: string): string {
  const schemeEnd = value.indexOf("://");
  if (schemeEnd < 0) return value;
  const pathStart = value.indexOf("/", schemeEnd + 3);
  return pathStart < 0 ? value : value.slice(0, pathStart);
}

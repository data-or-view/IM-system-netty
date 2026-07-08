import { useEffect, useRef } from "react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { APP_BEHAVIOR } from "@/config/app-behavior";
import {
  toAppErrorNotice,
  type AppErrorNotice,
} from "@/lib/app-errors";
import { APP_EVENT_TYPES, listenAppEvent } from "@/lib/app-events";
import { createLogger } from "@/lib/logger";

const log = createLogger("ui.global-errors");

export default function GlobalErrorHandler() {
  const recentNoticesRef = useRef<Map<string, number>>(new Map());

  useEffect(() => {
    const showNotice = (notice: AppErrorNotice) => {
      const message = notice.message.trim() || "操作失败";
      const source = notice.source ? sourceLabel(notice.source) : "";
      const renderedMessage = source ? `${source}：${message}` : message;
      const key = `${notice.severity}:${notice.source ?? ""}:${message}`;
      const now = Date.now();
      const previousAt = recentNoticesRef.current.get(key) ?? 0;
      if (now - previousAt < APP_BEHAVIOR.errors.toastDedupeMs) {
        return;
      }
      recentNoticesRef.current.set(key, now);

      if (notice.severity === "error") {
        toast.error(renderedMessage);
      } else {
        toast(renderedMessage);
      }
    };

    const onWindowError = (event: ErrorEvent) => {
      log.error("window error captured", { error: event.error, message: event.message });
      showNotice(toAppErrorNotice(event.error ?? event.message, "页面运行异常", "runtime"));
    };

    const onUnhandledRejection = (event: PromiseRejectionEvent) => {
      log.error("unhandled promise rejection captured", { error: event.reason });
      showNotice(toAppErrorNotice(event.reason, "异步操作失败", "promise"));
    };

    const unsubscribeSdkError = im.on("error", (err) => {
      log.error("sdk error captured", { error: err });
      showNotice(toAppErrorNotice(err, "SDK 运行异常", "sdk"));
    });
    const unsubscribeAppError = listenAppEvent(APP_EVENT_TYPES.appError, (detail: AppErrorNotice) => {
      if (!detail) return;
      showNotice(detail);
    });

    window.addEventListener("error", onWindowError);
    window.addEventListener("unhandledrejection", onUnhandledRejection);

    return () => {
      unsubscribeSdkError();
      unsubscribeAppError();
      window.removeEventListener("error", onWindowError);
      window.removeEventListener("unhandledrejection", onUnhandledRejection);
    };
  }, []);

  return null;
}

function sourceLabel(source: string): string {
  switch (source) {
    case "auth-check":
      return "登录校验";
    case "login":
      return "登录";
    case "register":
      return "注册";
    case "sdk":
      return "通信";
    case "runtime":
      return "页面";
    case "promise":
      return "异步任务";
    case "route-boundary":
      return "页面渲染";
    default:
      return source;
  }
}

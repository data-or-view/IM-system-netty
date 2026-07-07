import { useEffect, useRef } from "react";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { APP_BEHAVIOR } from "@/config/app-behavior";
import {
  APP_ERROR_EVENT,
  toAppErrorNotice,
  type AppErrorNotice,
} from "@/lib/app-errors";

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
      showNotice(toAppErrorNotice(event.error ?? event.message, "页面运行异常", "runtime"));
    };

    const onUnhandledRejection = (event: PromiseRejectionEvent) => {
      showNotice(toAppErrorNotice(event.reason, "异步操作失败", "promise"));
    };

    const onAppError = (event: Event) => {
      const detail = (event as CustomEvent<AppErrorNotice>).detail;
      if (!detail) return;
      showNotice(detail);
    };

    const unsubscribeSdkError = im.on("error", (err) => {
      showNotice(toAppErrorNotice(err, "SDK 运行异常", "sdk"));
    });

    window.addEventListener("error", onWindowError);
    window.addEventListener("unhandledrejection", onUnhandledRejection);
    window.addEventListener(APP_ERROR_EVENT, onAppError);

    return () => {
      unsubscribeSdkError();
      window.removeEventListener("error", onWindowError);
      window.removeEventListener("unhandledrejection", onUnhandledRejection);
      window.removeEventListener(APP_ERROR_EVENT, onAppError);
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
    default:
      return source;
  }
}

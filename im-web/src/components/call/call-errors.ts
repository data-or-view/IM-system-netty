export function callErrorText(err: unknown, fallback: string, sfuUrl?: string): string {
  const text = err instanceof Error ? err.message : String(err ?? "");
  const lower = text.toLowerCase();
  if (lower.includes("permission") || lower.includes("notallowed") || lower.includes("denied")) {
    return "摄像头或麦克风权限不可用，请检查浏览器权限";
  }
  if (lower.includes("timeout") || lower.includes("websocket") || lower.includes("connect")) {
    if (sfuUrl && (sfuUrl.includes("localhost") || sfuUrl.includes("127.0.0.1"))) {
      return `媒体服务地址配置为 ${sfuUrl}（仅限本机）。跨机器通话需将服务端 im.call.sfu-endpoint 改为公网 IP`;
    }
    return `媒体服务连接失败（${sfuUrl ?? "unknown"}），请确认 LiveKit 已启动且地址可访问`;
  }
  if (lower.includes("blocked by target user")) {
    return "对方已将你拉黑，无法发起通话";
  }
  if (lower.includes("对方已删除你") || lower.includes("forbidden") || lower.includes("403")) {
    return "当前关系不允许发起通话，请先确认好友或群成员状态";
  }
  if (lower.includes("not a group member")) {
    return "你已不在该群聊中，无法加入群视频";
  }
  if (lower.includes("not found") || lower.includes("not_active") || lower.includes("inactive")) {
    return "通话已结束或对方尚未接入";
  }
  if (lower.includes("conflict") || lower.includes("busy") || lower.includes("409")) {
    return "当前已有通话或对方正在通话中";
  }
  return fallback;
}

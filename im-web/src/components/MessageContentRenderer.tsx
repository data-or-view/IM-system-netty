import {
  MessageContentType,
  SignalingAction,
  normalizeSignalingContent,
  parseMessageContent,
  type ParsedMessageContent,
} from "im-sdk";
import { FileText, MapPin, Mic, Package, Phone, Quote, Video } from "lucide-react";
import type { Message } from "@/store/store";
import { localizeSystemMessage } from "@/lib/display-formatters";
import { REVOKED_MESSAGE_TEXT } from "@/lib/messages";
import { safeExternalUrl, safeMediaUrl } from "@/lib/safe-url";

type Props = {
  message: Pick<Message, "contentType" | "content" | "conversationId">;
};

export function MessageContentRenderer({ message }: Props) {
  const parsed = parseMessageContent(message);

  switch (parsed.type) {
    case MessageContentType.TEXT:
      return <TextBlock text={parsed.content.text} />;
    case MessageContentType.AT_TEXT:
      return <AtTextBlock text={parsed.content.text} atUserList={parsed.content.atUserList} />;
    case MessageContentType.QUOTE:
      return <QuoteBlock parsed={parsed} />;
    case MessageContentType.IMAGE:
      return <ImageBlock parsed={parsed} />;
    case MessageContentType.FILE:
      return <FileBlock parsed={parsed} />;
    case MessageContentType.VOICE:
      return <VoiceBlock parsed={parsed} />;
    case MessageContentType.VIDEO:
      return <VideoBlock parsed={parsed} />;
    case MessageContentType.LOCATION:
      return <LocationBlock parsed={parsed} />;
    case MessageContentType.SYSTEM:
      return <SystemBlock text={systemText(parsed.content.systemType, parsed.content.message || parsed.raw)} />;
    case MessageContentType.SIGNAL:
      return <InfoBlock icon={<Phone className="h-4 w-4" />} {...signalDisplay(parsed.content, message.conversationId?.startsWith("group_"))} />;
    case MessageContentType.CUSTOM:
      return <InfoBlock icon={<Package className="h-4 w-4" />} title={parsed.content.description || "自定义消息"} detail={customMessageDetail(parsed.content.data)} />;
    case MessageContentType.REVOKED:
      return <SystemBlock text={parsed.content.text || REVOKED_MESSAGE_TEXT} />;
    default:
      return <UnsupportedBlock />;
  }
}

function TextBlock({ text }: { text: string }) {
  return <div className="whitespace-pre-wrap break-words leading-relaxed">{text}</div>;
}

function AtTextBlock({ text, atUserList }: { text: string; atUserList?: string[] }) {
  return (
    <div className="space-y-1">
      <TextBlock text={text} />
      {atUserList && atUserList.length > 0 && (
        <div className="text-[11px] opacity-70">提到了 {atUserList.join("、")}</div>
      )}
    </div>
  );
}

function QuoteBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.QUOTE }> }) {
  return (
    <div className="space-y-2">
      <div className="rounded-md border-l-2 border-current/30 bg-background/30 px-2 py-1 text-xs opacity-80">
        <div className="flex items-center gap-1 font-medium">
          <Quote className="h-3 w-3" />
          引用 {parsed.content.quotedSenderId || "消息"}
        </div>
        {parsed.content.quotedContent && <div className="mt-1 line-clamp-2">{parsed.content.quotedContent}</div>}
      </div>
      <TextBlock text={parsed.content.text} />
    </div>
  );
}

function ImageBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.IMAGE }> }) {
  const picture = parsed.content.snapshotPicture || parsed.content.bigPicture || parsed.content.sourcePicture;
  const imageUrl = safeMediaUrl(picture?.url);
  const target = safeExternalUrl(parsed.content.sourcePicture?.url || picture?.url);
  if (!imageUrl) return <UnsupportedBlock />;

  const image = (
    <img
      src={imageUrl}
      alt="图片消息"
      className="max-h-72 max-w-full object-cover"
      loading="lazy"
    />
  );
  if (!target) {
    return <div className="block overflow-hidden rounded-lg border bg-background/40">{image}</div>;
  }

  return (
    <a href={target} target="_blank" rel="noopener noreferrer" className="block overflow-hidden rounded-lg border bg-background/40">
      {image}
    </a>
  );
}

function FileBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.FILE }> }) {
  const fileUrl = safeExternalUrl(parsed.content.url);
  const content = (
    <>
      <FileText className="h-8 w-8 shrink-0" />
      <div className="min-w-0">
        <div className="truncate text-sm font-medium">{parsed.content.fileName}</div>
        <div className="text-xs opacity-70">{fileUrl ? formatBytes(parsed.content.fileSize) : "文件链接不可用"}</div>
      </div>
    </>
  );
  if (!fileUrl) {
    return (
      <div className="flex min-w-52 items-center gap-3 rounded-lg border bg-background/40 p-3">
        {content}
      </div>
    );
  }
  return (
    <a
      href={fileUrl}
      target="_blank"
      rel="noopener noreferrer"
      className="flex min-w-52 items-center gap-3 rounded-lg border bg-background/40 p-3 transition-colors hover:bg-background/60"
    >
      {content}
    </a>
  );
}

function VoiceBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.VOICE }> }) {
  const voiceUrl = safeMediaUrl(parsed.content.url);
  if (!voiceUrl) return <UnsupportedBlock />;
  return (
    <div className="flex min-w-52 items-center gap-3 rounded-lg border bg-background/40 p-3">
      <Mic className="h-5 w-5" />
      <audio controls src={voiceUrl} className="h-8 max-w-48" />
      <span className="text-xs opacity-70">{parsed.content.duration}s</span>
    </div>
  );
}

function VideoBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.VIDEO }> }) {
  const videoUrl = safeMediaUrl(parsed.content.videoUrl);
  const posterUrl = safeMediaUrl(parsed.content.snapshotUrl);
  if (!videoUrl) return <UnsupportedBlock />;
  return (
    <div className="overflow-hidden rounded-lg border bg-background/40">
      <video
        controls
        preload="metadata"
        poster={posterUrl}
        src={videoUrl}
        className="max-h-72 max-w-full bg-black"
      />
      <div className="flex items-center gap-2 px-3 py-2 text-xs opacity-75">
        <Video className="h-3.5 w-3.5" />
        {formatDuration(parsed.content.duration)} · {formatBytes(parsed.content.videoSize)}
      </div>
    </div>
  );
}

function LocationBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.LOCATION }> }) {
  const label = parsed.content.description || `${parsed.content.latitude}, ${parsed.content.longitude}`;
  const url = `https://www.google.com/maps?q=${parsed.content.latitude},${parsed.content.longitude}`;
  return (
    <a href={url} target="_blank" rel="noopener noreferrer" className="flex min-w-52 items-center gap-3 rounded-lg border bg-background/40 p-3 transition-colors hover:bg-background/60">
      <MapPin className="h-5 w-5" />
      <div>
        <div className="text-sm font-medium">{label}</div>
        <div className="text-xs opacity-70">查看位置</div>
      </div>
    </a>
  );
}

function SystemBlock({ text }: { text: string }) {
  return <span className="text-xs opacity-75">{text}</span>;
}

function systemText(systemType?: string, message?: string): string {
  switch (systemType) {
    case "group_created":
      return message || "群聊已创建";
    case "group_member_joined":
      return message || "有成员加入群聊";
    case "group_member_left":
      return message || "有成员离开群聊";
    case "group_member_kicked":
      return message || "有成员被移出群聊";
    case "group_disbanded":
      return message || "群聊已解散";
    case "group_info_updated":
      return message || "群资料已更新";
    case "group_role_changed":
      return message || "群成员权限已变更";
    default:
      return message || systemType || "系统消息";
  }
}

export function isCompactSystemMessage(message: Pick<Message, "contentType" | "content">): boolean {
  const parsed = parseMessageContent(message);
  return parsed.type === MessageContentType.SYSTEM || parsed.type === MessageContentType.REVOKED;
}

export function compactSystemMessageText(message: Pick<Message, "contentType" | "content">): string {
  const parsed = parseMessageContent(message);
  if (parsed.type === MessageContentType.REVOKED) return parsed.content.text || REVOKED_MESSAGE_TEXT;
  if (parsed.type !== MessageContentType.SYSTEM) return "";
  return systemText(parsed.content.systemType, localizeSystemMessage(parsed.content.systemType, parsed.content.message || parsed.raw));
}

function InfoBlock({ icon, title, detail }: { icon: React.ReactNode; title: string; detail?: string }) {
  return (
    <div className="flex min-w-52 items-center gap-3 rounded-lg border bg-background/40 p-3">
      {icon}
      <div className="min-w-0">
        <div className="text-sm font-medium">{title}</div>
        {detail && <div className="truncate text-xs opacity-70">{detail}</div>}
      </div>
    </div>
  );
}

function UnsupportedBlock() {
  return (
    <div className="rounded-md border border-dashed bg-background/30 px-3 py-2 text-xs opacity-75">
      当前版本暂不支持这类消息
    </div>
  );
}

function customMessageDetail(data: unknown): string | undefined {
  if (typeof data === "string") return data.length > 80 ? `${data.slice(0, 80)}...` : data;
  if (data == null) return undefined;
  return "包含自定义内容";
}

function formatBytes(size?: number): string {
  if (!size || size <= 0) return "未知大小";
  const units = ["B", "KB", "MB", "GB"];
  let value = size;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(value >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
}

function formatDuration(duration?: number): string {
  if (!duration || duration <= 0) return "未知时长";
  const minute = Math.floor(duration / 60);
  const second = duration % 60;
  return minute > 0 ? `${minute}:${second.toString().padStart(2, "0")}` : `${second}s`;
}

function signalDisplay(content: Extract<ParsedMessageContent, { type: typeof MessageContentType.SIGNAL }>["content"], isGroup?: boolean): { title: string; detail?: string } {
  const signal = normalizeSignalingContent(content);
  const callType = isGroup ? "群视频会议" : signal?.callType === "video" ? "视频通话" : "语音通话";
  if (!signal) return { title: "音视频通话" };
  switch (signal.action) {
    case SignalingAction.CALLING:
    case SignalingAction.INVITE:
      return { title: callType, detail: isGroup ? "已发起" : "正在呼叫" };
    case SignalingAction.ACCEPT:
      return { title: callType, detail: isGroup ? "有成员加入" : "已接听" };
    case SignalingAction.REJECT:
      return { title: callType, detail: signal.reason === "busy" ? "对方忙线" : "已拒绝" };
    case SignalingAction.CANCEL:
      return { title: callType, detail: isGroup ? "有成员离开" : "已取消" };
    case SignalingAction.HANGUP:
      return { title: callType, detail: signal.duration && signal.duration > 0 ? `通话 ${formatDuration(signal.duration)}` : isGroup ? "会议已结束" : "已结束" };
    case SignalingAction.TIMEOUT:
      return { title: callType, detail: "未接听" };
    default:
      return { title: callType };
  }
}

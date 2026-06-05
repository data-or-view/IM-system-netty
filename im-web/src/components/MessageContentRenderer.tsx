import {
  MessageContentType,
  parseMessageContent,
  type ParsedMessageContent,
} from "im-sdk";
import { FileText, MapPin, Mic, Package, Phone, Quote, Video } from "lucide-react";
import type { Message } from "@/store/store";

type Props = {
  message: Pick<Message, "contentType" | "content">;
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
      return <SystemBlock text={parsed.content.message || parsed.content.systemType || parsed.raw || "系统消息"} />;
    case MessageContentType.SIGNAL:
      return <InfoBlock icon={<Phone className="h-4 w-4" />} title="音视频通话" detail={signalText(parsed.content.duration)} />;
    case MessageContentType.CUSTOM:
      return <InfoBlock icon={<Package className="h-4 w-4" />} title={parsed.content.description || "自定义消息"} detail={parsed.content.data} />;
    case MessageContentType.REVOKED:
      return <SystemBlock text={parsed.content.text || "消息已撤回"} />;
    default:
      return <UnsupportedBlock parsed={parsed} />;
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
  const target = parsed.content.sourcePicture?.url || picture?.url;
  if (!picture?.url) return <UnsupportedBlock parsed={parsed} />;

  return (
    <a href={target} target="_blank" rel="noreferrer" className="block overflow-hidden rounded-lg border bg-background/40">
      <img
        src={picture.url}
        alt="图片消息"
        className="max-h-72 max-w-full object-cover"
        loading="lazy"
      />
    </a>
  );
}

function FileBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.FILE }> }) {
  return (
    <a
      href={parsed.content.url}
      target="_blank"
      rel="noreferrer"
      className="flex min-w-52 items-center gap-3 rounded-lg border bg-background/40 p-3 transition-colors hover:bg-background/60"
    >
      <FileText className="h-8 w-8 shrink-0" />
      <div className="min-w-0">
        <div className="truncate text-sm font-medium">{parsed.content.fileName}</div>
        <div className="text-xs opacity-70">{formatBytes(parsed.content.fileSize)}</div>
      </div>
    </a>
  );
}

function VoiceBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.VOICE }> }) {
  return (
    <div className="flex min-w-52 items-center gap-3 rounded-lg border bg-background/40 p-3">
      <Mic className="h-5 w-5" />
      <audio controls src={parsed.content.url} className="h-8 max-w-48" />
      <span className="text-xs opacity-70">{parsed.content.duration}s</span>
    </div>
  );
}

function VideoBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: typeof MessageContentType.VIDEO }> }) {
  return (
    <div className="overflow-hidden rounded-lg border bg-background/40">
      <video
        controls
        preload="metadata"
        poster={parsed.content.snapshotUrl}
        src={parsed.content.videoUrl}
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
    <a href={url} target="_blank" rel="noreferrer" className="flex min-w-52 items-center gap-3 rounded-lg border bg-background/40 p-3 transition-colors hover:bg-background/60">
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

function UnsupportedBlock({ parsed }: { parsed: Extract<ParsedMessageContent, { type: "unknown" }> | ParsedMessageContent }) {
  return (
    <div className="rounded-md border border-dashed bg-background/30 px-3 py-2 text-xs opacity-75">
      暂不支持的消息类型
      {"contentType" in parsed && parsed.contentType ? `：${parsed.contentType}` : ""}
    </div>
  );
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

function signalText(duration?: number): string | undefined {
  return duration && duration > 0 ? `通话 ${formatDuration(duration)}` : undefined;
}

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Camera, CameraOff, Mic, MicOff, Phone, PhoneOff, Video } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { useCall, type CallState, type RemoteMedia } from "./CallProvider";

export function CallDialog() {
  const {
    call,
    acceptCall,
    rejectCall,
    cancelCall,
    hangupCall,
    toggleMute,
    toggleCamera,
  } = useCall();
  const open = call.phase !== "idle";
  const title = call.mode === "group"
    ? `${call.group?.name || "群聊"} 群视频`
    : call.peer?.name || call.peer?.userId || "未知用户";

  return (
    <Dialog open={open}>
      <DialogContent
        hideClose
        onOpenAutoFocus={(event) => event.preventDefault()}
        className="overflow-hidden border-white/10 bg-zinc-950 p-0 text-white shadow-2xl sm:max-w-[720px]"
      >
        <DialogTitle className="sr-only">
          {call.mode === "group" ? "群视频" : call.callType === "video" ? "视频通话" : "语音通话"}
        </DialogTitle>
        <div className="relative min-h-[560px] bg-[radial-gradient(circle_at_top,#14532d_0%,#18181b_42%,#050505_100%)]">
          {call.mode === "group" ? (
            <GroupStage call={call} title={title} />
          ) : call.callType === "video" && call.phase === "connected" ? (
            <VideoStage call={call} peerName={title} />
          ) : (
            <VoiceStage call={call} peerName={title} />
          )}

          <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black via-black/75 to-transparent px-6 pb-8 pt-24">
            <CallStatus call={call} title={title} />
            <CallActions
              call={call}
              onAccept={acceptCall}
              onReject={rejectCall}
              onCancel={cancelCall}
              onHangup={hangupCall}
              onToggleMute={toggleMute}
              onToggleCamera={toggleCamera}
            />
          </div>

          <RemoteAudio call={call} />
        </div>
      </DialogContent>
    </Dialog>
  );
}

function VoiceStage({ call, peerName }: { call: CallState; peerName: string }) {
  return (
    <div className="flex min-h-[560px] flex-col items-center justify-center px-8 pb-36 pt-16 text-center">
      <div className="relative">
        <div className="absolute inset-0 animate-ping rounded-full bg-emerald-400/20" />
        <Avatar className="relative h-28 w-28 border border-white/20 shadow-2xl">
          <AvatarImage src={call.peer?.faceUrl} />
          <AvatarFallback className="bg-emerald-500 text-4xl font-semibold text-white">
            {peerName.charAt(0).toUpperCase()}
          </AvatarFallback>
        </Avatar>
      </div>
      <div className="mt-7 text-2xl font-semibold tracking-tight">{peerName}</div>
      <div className="mt-2 text-sm text-white/65">
        {call.callType === "video" ? "视频通话" : "语音通话"}
      </div>
    </div>
  );
}

function VideoStage({ call, peerName }: { call: CallState; peerName: string }) {
  const remoteRef = useRef<HTMLVideoElement | null>(null);
  const localRef = useRef<HTMLVideoElement | null>(null);

  useAttachTrack(call.remoteVideoTrack, remoteRef);
  useAttachTrack(call.localVideoTrack, localRef);

  return (
    <div className="relative min-h-[560px] bg-black">
      {call.remoteVideoTrack ? (
        <video ref={remoteRef} autoPlay playsInline className="h-[560px] w-full object-cover" />
      ) : (
        <div className="flex min-h-[560px] flex-col items-center justify-center px-8 pb-36 text-center">
          <Avatar className="h-24 w-24 border border-white/20">
            <AvatarImage src={call.peer?.faceUrl} />
            <AvatarFallback className="bg-zinc-800 text-3xl text-white">
              {peerName.charAt(0).toUpperCase()}
            </AvatarFallback>
          </Avatar>
          <div className="mt-5 text-lg font-medium">等待对方视频画面...</div>
        </div>
      )}

      {call.localVideoTrack && !call.cameraOff && (
        <div className="absolute right-4 top-4 h-36 w-24 overflow-hidden rounded-2xl border border-white/20 bg-zinc-900 shadow-2xl">
          <video ref={localRef} autoPlay muted playsInline className="h-full w-full object-cover" />
        </div>
      )}
    </div>
  );
}

function GroupStage({ call, title }: { call: CallState; title: string }) {
  const localRef = useRef<HTMLVideoElement | null>(null);
  useAttachTrack(call.localVideoTrack, localRef);
  const tiles = call.remoteMedias.slice(0, 8);

  return (
    <div className="min-h-[560px] bg-black px-5 pb-36 pt-6">
      <div className="mb-4 flex items-center justify-between text-white/80">
        <div>
          <div className="text-lg font-semibold text-white">{title}</div>
          <div className="text-xs text-white/55">{tiles.length + 1} 人正在通话</div>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <LocalTile call={call} videoRef={localRef} />
        {tiles.map((media) => <RemoteTile key={media.participantId} media={media} />)}
      </div>
    </div>
  );
}

function LocalTile({ call, videoRef }: { call: CallState; videoRef: React.RefObject<HTMLVideoElement> }) {
  return (
    <div className="relative aspect-video overflow-hidden rounded-2xl border border-white/10 bg-zinc-900">
      {call.localVideoTrack && !call.cameraOff ? (
        <video ref={videoRef} autoPlay muted playsInline className="h-full w-full object-cover" />
      ) : (
        <TileFallback name="我" />
      )}
      <div className="absolute bottom-2 left-2 rounded-full bg-black/55 px-2 py-1 text-xs">我</div>
    </div>
  );
}

function RemoteTile({ media }: { media: RemoteMedia }) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  useAttachTrack(media.videoTrack, videoRef);
  return (
    <div className="relative aspect-video overflow-hidden rounded-2xl border border-white/10 bg-zinc-900">
      {media.videoTrack ? (
        <video ref={videoRef} autoPlay playsInline className="h-full w-full object-cover" />
      ) : (
        <TileFallback name={media.name || media.participantId} />
      )}
      <div className="absolute bottom-2 left-2 rounded-full bg-black/55 px-2 py-1 text-xs">
        {media.name || media.participantId}
      </div>
    </div>
  );
}

function TileFallback({ name }: { name: string }) {
  return (
    <div className="flex h-full w-full items-center justify-center bg-[radial-gradient(circle_at_top,#064e3b,#18181b)]">
      <div className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500 text-xl font-semibold text-white">
        {name.charAt(0).toUpperCase()}
      </div>
    </div>
  );
}

function RemoteAudio({ call }: { call: CallState }) {
  if (call.mode === "group") {
    return <>{call.remoteMedias.map((media) => <RemoteAudioTrack key={media.participantId} track={media.audioTrack} />)}</>;
  }
  return <RemoteAudioTrack track={call.remoteAudioTrack} />;
}

function RemoteAudioTrack({ track }: { track?: { attach(element: HTMLMediaElement): HTMLMediaElement; detach(element: HTMLMediaElement): HTMLMediaElement } }) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  useAttachTrack(track, audioRef);
  return <audio ref={audioRef} autoPlay className="hidden" />;
}

function CallStatus({ call, title }: { call: CallState; title: string }) {
  const duration = useCallDuration(call.startedAt, call.phase === "connected");

  const text = useMemo(() => {
    if (call.mode === "group") {
      if (call.phase === "connecting") return "正在加入群视频...";
      return duration;
    }
    if (call.phase === "incoming") {
      return `${title} 邀请你${call.callType === "video" ? "视频" : "语音"}通话`;
    }
    if (call.phase === "outgoing") return "正在呼叫对方...";
    if (call.phase === "connecting") return "正在接入通话...";
    return duration;
  }, [call.callType, call.mode, call.phase, duration, title]);

  return (
    <div className="mb-7 text-center">
      <div className="text-sm font-medium text-white/90">{text}</div>
      {call.phase !== "connected" && (
        <div className="mt-2 text-xs text-white/50">请保持页面打开，媒体连接由 LiveKit 承载</div>
      )}
    </div>
  );
}

function CallActions({
  call,
  onAccept,
  onReject,
  onCancel,
  onHangup,
  onToggleMute,
  onToggleCamera,
}: {
  call: CallState;
  onAccept: () => Promise<void>;
  onReject: () => Promise<void>;
  onCancel: () => Promise<void>;
  onHangup: () => Promise<void>;
  onToggleMute: () => Promise<void>;
  onToggleCamera: () => Promise<void>;
}) {
  if (call.phase === "incoming") {
    return (
      <div className="flex items-center justify-center gap-14">
        <RoundButton label="拒绝" tone="danger" onClick={() => void onReject()}>
          <PhoneOff className="h-6 w-6" />
        </RoundButton>
        <RoundButton label="接听" tone="accept" onClick={() => void onAccept()}>
          {call.callType === "video" ? <Video className="h-6 w-6" /> : <Phone className="h-6 w-6" />}
        </RoundButton>
      </div>
    );
  }

  if (call.phase === "outgoing" || call.phase === "connecting") {
    return (
      <div className="flex items-center justify-center">
        <RoundButton label="取消" tone="danger" onClick={() => void onCancel()}>
          <PhoneOff className="h-6 w-6" />
        </RoundButton>
      </div>
    );
  }

  return (
    <div className="flex items-center justify-center gap-7">
      <RoundButton label={call.muted ? "取消静音" : "静音"} onClick={() => void onToggleMute()}>
        {call.muted ? <MicOff className="h-5 w-5" /> : <Mic className="h-5 w-5" />}
      </RoundButton>
      {call.callType === "video" && (
        <RoundButton label={call.cameraOff ? "打开摄像头" : "关闭摄像头"} onClick={() => void onToggleCamera()}>
          {call.cameraOff ? <CameraOff className="h-5 w-5" /> : <Camera className="h-5 w-5" />}
        </RoundButton>
      )}
      <RoundButton label={call.mode === "group" ? "退出" : "挂断"} tone="danger" onClick={() => void onHangup()}>
        <PhoneOff className="h-6 w-6" />
      </RoundButton>
    </div>
  );
}

function RoundButton({
  children,
  label,
  tone = "default",
  onClick,
}: {
  children: ReactNode;
  label: string;
  tone?: "default" | "danger" | "accept";
  onClick: () => void;
}) {
  const toneClass = tone === "danger"
    ? "bg-red-500 text-white hover:bg-red-500/90"
    : tone === "accept"
      ? "bg-emerald-500 text-white hover:bg-emerald-500/90"
      : "bg-white/12 text-white hover:bg-white/20";

  return (
    <div className="flex flex-col items-center gap-2">
      <Button
        type="button"
        size="icon"
        onClick={onClick}
        className={`h-14 w-14 rounded-full border border-white/10 shadow-lg backdrop-blur ${toneClass}`}
      >
        {children}
      </Button>
      <span className="text-xs text-white/70">{label}</span>
    </div>
  );
}

function useAttachTrack<T extends { attach(element: HTMLMediaElement): HTMLMediaElement; detach(element: HTMLMediaElement): HTMLMediaElement }>(
  track: T | undefined,
  ref: React.RefObject<HTMLMediaElement>,
) {
  useEffect(() => {
    const element = ref.current;
    if (!track || !element) return;
    track.attach(element);
    return () => {
      track.detach(element);
    };
  }, [track, ref]);
}

function useCallDuration(startedAt?: number, active?: boolean) {
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    if (!active || !startedAt) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [active, startedAt]);

  if (!startedAt) return "00:00";
  const seconds = Math.max(0, Math.floor((now - startedAt) / 1000));
  const minute = Math.floor(seconds / 60);
  const second = seconds % 60;
  return `${minute.toString().padStart(2, "0")}:${second.toString().padStart(2, "0")}`;
}

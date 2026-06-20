import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MutableRefObject,
  type ReactNode,
} from "react";
import {
  AudioPresets,
  ConnectionQuality,
  Room,
  RoomEvent,
  Track,
  VideoPresets,
  createLocalAudioTrack,
  createLocalVideoTrack,
  type LocalAudioTrack,
  type LocalVideoTrack,
  type Participant,
  type RemoteAudioTrack,
  type RemoteTrack,
  type RemoteTrackPublication,
  type RemoteVideoTrack,
  type RemoteParticipant,
  type RoomOptions,
  type TrackPublishOptions,
} from "livekit-client";
import {
  SignalingAction,
  type CallSignalEvent,
  type GroupCallParticipant,
  type SignalingActionName,
} from "im-sdk";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { useStore } from "@/store/store";
import { DEV_LIVEKIT_URL } from "@/config/runtime";
import { toOptimisticMessage } from "@/lib/messages";

export type CallType = "voice" | "video";
export type CallPhase = "idle" | "dialing" | "ringing" | "incoming" | "accepted" | "connectingMedia" | "connected" | "reconnecting" | "ending" | "ended";
export type CallMode = "single" | "group";

export type CallPeer = {
  userId: string;
  name?: string;
  faceUrl?: string;
};

export type GroupCallTarget = {
  groupId: string;
  name?: string;
  faceUrl?: string;
  canEnd?: boolean;
};

export type RemoteMedia = {
  participantId: string;
  name?: string;
  audioTrack?: RemoteAudioTrack;
  videoTrack?: RemoteVideoTrack;
};

export type CallState = {
  phase: CallPhase;
  mode: CallMode;
  callType: CallType;
  direction?: "incoming" | "outgoing";
  peer?: CallPeer;
  group?: GroupCallTarget;
  roomId?: string;
  startedAt?: number;
  muted: boolean;
  cameraOff: boolean;
  localVideoTrack?: LocalVideoTrack;
  remoteAudioTrack?: RemoteAudioTrack;
  remoteVideoTrack?: RemoteVideoTrack;
  remoteMedias: RemoteMedia[];
  initiatorUserId?: string;
  participantCount?: number;
  participants?: GroupCallParticipant[];
  endReason?: string;
};

type StartCallInput = {
  peer: CallPeer;
  callType: CallType;
};

type StartGroupCallInput = {
  group: GroupCallTarget;
  callType?: CallType;
};

type JoinGroupCallInput = {
  group: GroupCallTarget;
  mediaPermissionChecked?: boolean;
};

type CallContextValue = {
  call: CallState;
  startCall: (input: StartCallInput) => Promise<void>;
  startGroupCall: (input: StartGroupCallInput) => Promise<void>;
  joinGroupCall: (input: JoinGroupCallInput) => Promise<void>;
  acceptCall: () => Promise<void>;
  rejectCall: () => Promise<void>;
  cancelCall: () => Promise<void>;
  hangupCall: () => Promise<void>;
  endGroupCall: () => Promise<void>;
  toggleMute: () => Promise<void>;
  toggleCamera: () => Promise<void>;
};

const AUDIO_CAPTURE_OPTIONS = {
  echoCancellation: true,
  noiseSuppression: true,
  autoGainControl: true,
  channelCount: 1,
} as const;
const VIDEO_CAPTURE_OPTIONS = {
  resolution: VideoPresets.h540.resolution,
  frameRate: 24,
} as const;
const AUDIO_PUBLISH_OPTIONS: TrackPublishOptions = {
  source: Track.Source.Microphone,
  audioPreset: AudioPresets.speech,
  dtx: true,
  red: true,
};
const VIDEO_PUBLISH_OPTIONS: TrackPublishOptions = {
  source: Track.Source.Camera,
  videoEncoding: VideoPresets.h540.encoding,
  videoSimulcastLayers: [VideoPresets.h180, VideoPresets.h360],
  simulcast: true,
  degradationPreference: "maintain-framerate",
};
const LIVEKIT_ROOM_OPTIONS: RoomOptions = {
  adaptiveStream: true,
  dynacast: true,
  audioCaptureDefaults: AUDIO_CAPTURE_OPTIONS,
  videoCaptureDefaults: VIDEO_CAPTURE_OPTIONS,
  publishDefaults: {
    audioPreset: AudioPresets.speech,
    videoEncoding: VideoPresets.h540.encoding,
    videoSimulcastLayers: [VideoPresets.h180, VideoPresets.h360],
    simulcast: true,
    dtx: true,
    red: true,
    degradationPreference: "maintain-framerate",
  },
};
const EMPTY_CALL: CallState = {
  phase: "idle",
  mode: "single",
  callType: "voice",
  muted: false,
  cameraOff: false,
  remoteMedias: [],
};

const CallContext = createContext<CallContextValue | null>(null);

export function CallProvider({ children }: { children: ReactNode }) {
  const { state, dispatch } = useStore();
  const [call, setCall] = useState<CallState>(EMPTY_CALL);
  const callRef = useRef<CallState>(EMPTY_CALL);
  const roomRef = useRef<Room | null>(null);
  const localAudioRef = useRef<LocalAudioTrack | null>(null);
  const localVideoRef = useRef<LocalVideoTrack | null>(null);
  const incomingTokenRef = useRef<string | null>(null);
  const outgoingTokenRef = useRef<string | null>(null);
  const liveKitUrlRef = useRef<string>(import.meta.env.VITE_LIVEKIT_URL ?? DEV_LIVEKIT_URL);
  const weakNetworkNotifiedRef = useRef(false);
  const seenSignalsRef = useRef(new Set<string>());
  const titleBlinkTimerRef = useRef<number | null>(null);
  const originalTitleRef = useRef(typeof document !== "undefined" ? document.title : "");
  const ringtoneRef = useRef<AudioContext | null>(null);

  useEffect(() => {
    callRef.current = call;
  }, [call]);

  const disconnectCurrentRoom = useCallback(async () => {
    localAudioRef.current?.stop();
    localAudioRef.current = null;
    localVideoRef.current?.stop();
    localVideoRef.current = null;
    roomRef.current?.disconnect();
    roomRef.current = null;
  }, []);

  const resetCall = useCallback(async () => {
    await disconnectCurrentRoom();
    incomingTokenRef.current = null;
    outgoingTokenRef.current = null;
    seenSignalsRef.current.clear();
    stopIncomingAttention(titleBlinkTimerRef, ringtoneRef, originalTitleRef.current);
    setCall(EMPTY_CALL);
  }, [disconnectCurrentRoom]);

  const connectRoom = useCallback(async (url: string, token: string, nextCallType: CallType) => {
    await disconnectCurrentRoom();

    // Group calls can grow quickly, so let LiveKit stop unused layers instead of
    // forcing every browser to send and receive the highest quality stream.
    const room = new Room(LIVEKIT_ROOM_OPTIONS);
    roomRef.current = room;

    const upsertRemote = (participant: RemoteParticipant, patch: Partial<RemoteMedia>) => {
      setCall((prev) => {
        const id = participant.identity;
        const existing = prev.remoteMedias.find((item) => item.participantId === id);
        const nextItem: RemoteMedia = {
          participantId: id,
          name: participant.name || id,
          ...existing,
          ...patch,
        };
        const remoteMedias = existing
          ? prev.remoteMedias.map((item) => item.participantId === id ? nextItem : item)
          : [...prev.remoteMedias, nextItem];
        return {
          ...prev,
          remoteMedias,
          remoteAudioTrack: nextItem.audioTrack ?? prev.remoteAudioTrack,
          remoteVideoTrack: nextItem.videoTrack ?? prev.remoteVideoTrack,
        };
      });
    };

    const handleTrackSubscribed = (
      track: RemoteTrack,
      _publication: RemoteTrackPublication,
      participant: RemoteParticipant,
    ) => {
      if (track.kind === Track.Kind.Audio) {
        upsertRemote(participant, { audioTrack: track as RemoteAudioTrack });
      } else if (track.kind === Track.Kind.Video) {
        upsertRemote(participant, { videoTrack: track as RemoteVideoTrack });
      }
    };

    const handleTrackUnsubscribed = (track: RemoteTrack, _publication: RemoteTrackPublication, participant: RemoteParticipant) => {
      setCall((prev) => {
        const remoteMedias = prev.remoteMedias.map((item) => {
          if (item.participantId !== participant.identity) return item;
          return {
            ...item,
            audioTrack: item.audioTrack === track ? undefined : item.audioTrack,
            videoTrack: item.videoTrack === track ? undefined : item.videoTrack,
          };
        });
        return {
          ...prev,
          remoteMedias,
          remoteAudioTrack: prev.remoteAudioTrack === track ? undefined : prev.remoteAudioTrack,
          remoteVideoTrack: prev.remoteVideoTrack === track ? undefined : prev.remoteVideoTrack,
        };
      });
    };

    room.on(RoomEvent.TrackSubscribed, handleTrackSubscribed);
    room.on(RoomEvent.TrackUnsubscribed, handleTrackUnsubscribed);
    room.on(RoomEvent.ConnectionQualityChanged, (quality: ConnectionQuality, participant: Participant) => {
      if (!participant.isLocal) return;
      if (quality === ConnectionQuality.Poor || quality === ConnectionQuality.Lost) {
        if (!weakNetworkNotifiedRef.current) {
          toast("当前网络较弱，视频会自动降低清晰度以保证通话");
          weakNetworkNotifiedRef.current = true;
        }
      } else if (weakNetworkNotifiedRef.current && (quality === ConnectionQuality.Good || quality === ConnectionQuality.Excellent)) {
        toast("网络已恢复");
        weakNetworkNotifiedRef.current = false;
      }
    });
    room.on(RoomEvent.Reconnecting, () => {
      setCall((prev) => prev.phase === "connected" ? { ...prev, phase: "reconnecting" } : prev);
      toast("媒体连接正在恢复...");
    });
    room.on(RoomEvent.Reconnected, () => {
      setCall((prev) => prev.phase === "reconnecting" ? { ...prev, phase: "connected" } : prev);
      toast("媒体连接已恢复");
    });
    room.on(RoomEvent.MediaDevicesError, (_error, kind) => {
      toast(kind === "videoinput" ? "摄像头不可用，请检查浏览器权限" : "麦克风不可用，请检查浏览器权限");
    });
    room.on(RoomEvent.ParticipantDisconnected, (participant) => {
      setCall((prev) => ({
        ...prev,
        remoteMedias: prev.remoteMedias.filter((item) => item.participantId !== participant.identity),
      }));
    });
    room.on(RoomEvent.Disconnected, () => {
      localAudioRef.current = null;
      localVideoRef.current = null;
      roomRef.current = null;
      weakNetworkNotifiedRef.current = false;
      setCall((prev) => (
        prev.phase === "idle"
          ? prev
          : { ...prev, localVideoTrack: undefined, remoteAudioTrack: undefined, remoteVideoTrack: undefined, remoteMedias: [] }
      ));
    });

    await room.connect(url, token, {
      autoSubscribe: true,
      peerConnectionTimeout: 15000,
      websocketTimeout: 8000,  // fail fast — 3 × 15s was 45s
      maxRetries: 1,
    });

    const audioTrack = await createLocalAudioTrack(AUDIO_CAPTURE_OPTIONS);
    localAudioRef.current = audioTrack;
    await room.localParticipant.publishTrack(audioTrack, AUDIO_PUBLISH_OPTIONS);

    if (nextCallType === "video") {
      const videoTrack = await createLocalVideoTrack(VIDEO_CAPTURE_OPTIONS);
      localVideoRef.current = videoTrack;
      await room.localParticipant.publishTrack(videoTrack, VIDEO_PUBLISH_OPTIONS);
      setCall((prev) => ({ ...prev, localVideoTrack: videoTrack }));
    }
  }, [disconnectCurrentRoom]);

  const startCall = useCallback(async ({ peer, callType }: StartCallInput) => {
    if (!peer.userId || callRef.current.phase !== "idle") return;

    setCall({
      phase: "dialing",
      mode: "single",
      direction: "outgoing",
      callType,
      peer,
      muted: false,
      cameraOff: callType !== "video",
      remoteMedias: [],
    });

    try {
      await ensureMediaPermission(callType);
      const ack = await im.message.startCall({ toUserId: peer.userId, callType });
      liveKitUrlRef.current = ack.sfuEndpoint || liveKitUrlRef.current;
      outgoingTokenRef.current = ack.token;
      setCall((prev) => ({ ...prev, phase: "ringing", roomId: ack.roomId }));
    } catch (err) {
      console.error("start call failed:", err);
      toast(callErrorText(err, "发起通话失败", liveKitUrlRef.current));
      await resetCall();
    }
  }, [connectRoom, resetCall]);

  const joinGroupCall = useCallback(async ({ group, mediaPermissionChecked = false }: JoinGroupCallInput) => {
    if (!group.groupId || callRef.current.phase !== "idle") return;
    try {
      if (!mediaPermissionChecked) {
        await ensureMediaPermission("video");
      }
      const ack = await im.group.joinCall(group.groupId);
      const callType = ack.callType ?? "video";
      liveKitUrlRef.current = ack.sfuEndpoint || liveKitUrlRef.current;
      setCall({
        phase: "connectingMedia",
        mode: "group",
        callType,
        group,
        muted: false,
        cameraOff: callType !== "video",
        remoteMedias: [],
        roomId: ack.roomId,
        startedAt: ack.startedAt ?? Date.now(),
        initiatorUserId: ack.initiatorUserId,
        participantCount: ack.participantCount,
        participants: ack.participants,
      });
      await connectRoom(liveKitUrlRef.current, ack.token, callType);
      // Guard against race condition: user may have cancelled while connectRoom was running.
      // If phase is already "idle", don't reopen the dialog by setting "connected".
      if (callRef.current.phase === "idle") {
        await disconnectCurrentRoom();
        return;
      }
      setCall((prev) => ({
        ...prev,
        phase: "connected",
        mode: "group",
        callType,
        group,
        roomId: ack.roomId,
        startedAt: ack.startedAt ?? Date.now(),
        initiatorUserId: ack.initiatorUserId,
        participantCount: ack.participantCount,
        participants: ack.participants,
        cameraOff: callType !== "video",
      }));
    } catch (err) {
      console.error("join group call failed:", err);
      toast(callErrorText(err, "加入群视频失败", liveKitUrlRef.current));
      await resetCall();
    }
  }, [connectRoom, disconnectCurrentRoom, resetCall]);

  const startGroupCall = useCallback(async ({ group, callType = "video" }: StartGroupCallInput) => {
    if (!group.groupId || callRef.current.phase !== "idle") return;
    try {
      await ensureMediaPermission(callType);
      await im.group.startCall(group.groupId, callType);
      await joinGroupCall({ group, mediaPermissionChecked: true });
    } catch (err) {
      console.error("start group call failed:", err);
      toast(callErrorText(err, "发起群视频失败", liveKitUrlRef.current));
      await resetCall();
    }
  }, [joinGroupCall, resetCall]);

  const acceptCall = useCallback(async () => {
    const current = callRef.current;
    if (current.phase !== "incoming" || !current.peer?.userId || !current.roomId || !incomingTokenRef.current) return;

    try {
      stopIncomingAttention(titleBlinkTimerRef, ringtoneRef, originalTitleRef.current);
      await ensureMediaPermission(current.callType);
      setCall((prev) => ({ ...prev, phase: "accepted" }));
      await im.message.sendCallSignal(current.peer.userId, SignalingAction.ACCEPT, current.roomId);
      setCall((prev) => ({ ...prev, phase: "connectingMedia" }));
      await connectRoom(liveKitUrlRef.current, incomingTokenRef.current, current.callType);
      setCall((prev) => ({ ...prev, phase: "connected", startedAt: Date.now() }));
    } catch (err) {
      console.error("accept call failed:", err);
      toast(callErrorText(err, "接听失败"));
      await resetCall();
    }
  }, [connectRoom, resetCall]);

  const rejectCall = useCallback(async () => {
    const current = callRef.current;
    if (current.peer?.userId && current.roomId) {
      const ack = await im.message.sendCallSignal(current.peer.userId, SignalingAction.REJECT, current.roomId).catch(() => undefined);
      appendLocalCallSignal(ack, SignalingAction.REJECT, current, state.userId, dispatch);
    }
    await resetCall();
  }, [dispatch, resetCall, state.userId]);

  const endGroupCall = useCallback(async () => {
    const current = callRef.current;
    if (current.mode !== "group" || !current.group?.groupId) return;
    setCall((prev) => prev.phase === "idle" ? prev : { ...prev, phase: "ending" });
    try {
      await im.group.endCall(current.group.groupId);
      await resetCall();
    } catch (err) {
      toast(callErrorText(err, "结束群视频失败"));
      setCall((prev) => prev.phase === "ending" ? { ...prev, phase: "connected" } : prev);
    }
  }, [resetCall]);

  const cancelCall = useCallback(async () => {
    const current = callRef.current;
    if (current.mode === "group" && current.group?.groupId) {
      // For group calls: remove this user from the server-side participant list so
      // the participant count stays accurate even if the user never connected to LiveKit.
      await im.group.leaveCall(current.group.groupId).catch(() => undefined);
    } else if (current.peer?.userId && current.roomId) {
      const ack = await im.message.sendCallSignal(current.peer.userId, SignalingAction.CANCEL, current.roomId).catch(() => undefined);
      appendLocalCallSignal(ack, SignalingAction.CANCEL, current, state.userId, dispatch);
    }
    await resetCall();
  }, [dispatch, resetCall, state.userId]);

  const hangupCall = useCallback(async () => {
    const current = callRef.current;
    setCall((prev) => prev.phase === "idle" ? prev : { ...prev, phase: "ending" });
    const duration = current.startedAt ? Math.max(0, Math.floor((Date.now() - current.startedAt) / 1000)) : 0;
    if (current.mode === "group" && current.group?.groupId) {
      await im.group.leaveCall(current.group.groupId).catch(() => undefined);
    } else if (current.peer?.userId && current.roomId) {
      const ack = await im.message.sendCallSignal(current.peer.userId, SignalingAction.HANGUP, current.roomId, duration).catch(() => undefined);
      appendLocalCallSignal(ack, SignalingAction.HANGUP, current, state.userId, dispatch, duration);
    }
    await resetCall();
  }, [dispatch, resetCall, state.userId]);

  const toggleMute = useCallback(async () => {
    const nextMuted = !callRef.current.muted;
    if (nextMuted) {
      await localAudioRef.current?.mute();
    } else {
      await localAudioRef.current?.unmute();
    }
    setCall((prev) => ({ ...prev, muted: nextMuted }));
  }, []);

  const toggleCamera = useCallback(async () => {
    if (callRef.current.callType !== "video") return;
    const nextOff = !callRef.current.cameraOff;
    if (nextOff) {
      await localVideoRef.current?.mute();
    } else {
      await localVideoRef.current?.unmute();
    }
    setCall((prev) => ({ ...prev, cameraOff: nextOff }));
  }, []);

  const handleCallIncoming = useCallback((event: CallSignalEvent) => {
    const { message: msg, signal } = event;
    if (msg.fromUserId === state.userId) return;

    if (!signal.roomId || !signal.token) return;
    if (callRef.current.phase !== "idle") {
      void im.message.sendCallSignal(msg.fromUserId, SignalingAction.REJECT, signal.roomId, undefined, "busy")
        .catch(() => undefined);
      return;
    }

    incomingTokenRef.current = signal.token;
    liveKitUrlRef.current = signal.sfuEndpoint || liveKitUrlRef.current;
    setCall({
      phase: "incoming",
      mode: "single",
      direction: "incoming",
      callType: signal.callType ?? "voice",
      peer: resolvePeer(msg.fromUserId, state),
      roomId: signal.roomId,
      muted: false,
      cameraOff: signal.callType !== "video",
      remoteMedias: [],
    });
    startIncomingAttention(titleBlinkTimerRef, ringtoneRef, originalTitleRef.current, `${resolvePeer(msg.fromUserId, state).name || msg.fromUserId} 来电`);
  }, [state]);

  const handleCallSignal = useCallback((event: CallSignalEvent) => {
    const { message: msg, signal } = event;
    if (msg.fromUserId === state.userId) return;
    const key = `${signal.roomId || ""}:${signal.action}:${msg.messageId || msg.messageSeq || msg.timestamp}`;
    if (seenSignalsRef.current.has(key)) return;
    seenSignalsRef.current.add(key);
    if (seenSignalsRef.current.size > 200) {
      seenSignalsRef.current.clear();
    }
    const current = callRef.current;
    if (!signal.roomId || signal.roomId !== current.roomId) return;

    if (signal.action === SignalingAction.ACCEPT) {
      if (current.direction !== "outgoing" || !outgoingTokenRef.current) return;
      setCall((prev) => ({ ...prev, phase: "connectingMedia" }));
      void connectRoom(liveKitUrlRef.current, outgoingTokenRef.current, current.callType)
        .then(() => setCall((prev) => ({ ...prev, phase: "connected", startedAt: Date.now() })))
        .catch(async (err) => {
          console.error("connect accepted call failed:", err);
          toast(callErrorText(err, "接入通话失败"));
          await im.message.sendCallSignal(msg.fromUserId, SignalingAction.HANGUP, signal.roomId!, undefined, "media_failed")
            .catch(() => undefined);
          await resetCall();
        });
      return;
    }

    handleRemoteSignal(signal.action, signal.reason, resetCall);
  }, [connectRoom, resetCall, state.userId]);

  useEffect(() => {
    const unsubIncoming = im.on("callIncoming", handleCallIncoming);
    const unsubSignal = im.on("callSignal", handleCallSignal);
    return () => {
      unsubIncoming();
      unsubSignal();
    };
  }, [handleCallIncoming, handleCallSignal]);

  useEffect(() => () => {
    void disconnectCurrentRoom();
  }, [disconnectCurrentRoom]);

  const value = useMemo<CallContextValue>(() => ({
    call,
    startCall,
    startGroupCall,
    joinGroupCall,
    acceptCall,
    rejectCall,
    cancelCall,
    hangupCall,
    endGroupCall,
    toggleMute,
    toggleCamera,
  }), [acceptCall, call, cancelCall, endGroupCall, hangupCall, joinGroupCall, rejectCall, startCall, startGroupCall, toggleCamera, toggleMute]);

  return <CallContext.Provider value={value}>{children}</CallContext.Provider>;
}

function appendLocalCallSignal(
  ack: Awaited<ReturnType<typeof im.message.sendCallSignal>> | undefined,
  action: SignalingActionName,
  call: CallState,
  currentUserId: string | null,
  dispatch: ReturnType<typeof useStore>["dispatch"],
  duration?: number,
) {
  if (!ack) return;
  const msg = toOptimisticMessage(ack, currentUserId || "", "signal", {
    action,
    roomId: call.roomId,
    callType: call.callType,
    ...(duration !== undefined ? { duration } : {}),
  });
  dispatch({ type: "APPEND_MESSAGE", conversationId: ack.conversationId, msg });
}

export function useCall() {
  const ctx = useContext(CallContext);
  if (!ctx) throw new Error("useCall must be used within CallProvider");
  return ctx;
}

function resolvePeer(userId: string, state: ReturnType<typeof useStore>["state"]): CallPeer {
  const friend = state.friends.find((item) => item.friendUserId === userId);
  return {
    userId,
    name: friend?.remark || friend?.nickname || userId,
    faceUrl: friend?.faceUrl,
  };
}

function handleRemoteSignal(action: SignalingActionName, reason: string | undefined, resetCall: () => Promise<void>) {
  if (action === SignalingAction.REJECT) {
    toast(reason === "busy" ? "对方正在通话中" : "对方已拒绝");
    void resetCall();
  } else if (action === SignalingAction.CANCEL) {
    toast("对方已取消");
    void resetCall();
  } else if (action === SignalingAction.HANGUP) {
    toast("通话已结束");
    void resetCall();
  } else if (action === SignalingAction.TIMEOUT) {
    toast("无人接听");
    void resetCall();
  }
}

function callErrorText(err: unknown, fallback: string, sfuUrl?: string): string {
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
  if (lower.includes("not found") || lower.includes("not_active") || lower.includes("inactive")) {
    return "通话已结束或对方尚未接入";
  }
  if (lower.includes("conflict") || lower.includes("busy") || lower.includes("409")) {
    return "当前已有通话或对方正在通话中";
  }
  return fallback;
}

async function ensureMediaPermission(callType: CallType): Promise<void> {
  if (!navigator.mediaDevices?.getUserMedia) return;
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: AUDIO_CAPTURE_OPTIONS,
    video: callType === "video" ? VIDEO_CAPTURE_OPTIONS : false,
  });
  stream.getTracks().forEach((track) => track.stop());
}

function startIncomingAttention(
  titleTimerRef: MutableRefObject<number | null>,
  ringtoneRef: MutableRefObject<AudioContext | null>,
  originalTitle: string,
  title: string,
) {
  stopIncomingAttention(titleTimerRef, ringtoneRef, originalTitle);
  if (typeof document !== "undefined") {
    let visible = false;
    titleTimerRef.current = window.setInterval(() => {
      visible = !visible;
      document.title = visible ? title : originalTitle;
    }, 900);
  }
  try {
    const AudioCtor = window.AudioContext || (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtor) return;
    const ctx = new AudioCtor();
    ringtoneRef.current = ctx;
    const ring = () => {
      if (ringtoneRef.current !== ctx) return;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = 880;
      gain.gain.value = 0.04;
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start();
      window.setTimeout(() => osc.stop(), 180);
      window.setTimeout(ring, 1200);
    };
    void ctx.resume().then(ring).catch(() => undefined);
  } catch {
    // Browser autoplay rules may block audio until user gesture; title blinking still works.
  }
}

function stopIncomingAttention(
  titleTimerRef: MutableRefObject<number | null>,
  ringtoneRef: MutableRefObject<AudioContext | null>,
  originalTitle: string,
) {
  if (titleTimerRef.current !== null) {
    window.clearInterval(titleTimerRef.current);
    titleTimerRef.current = null;
  }
  if (typeof document !== "undefined") {
    document.title = originalTitle;
  }
  const ctx = ringtoneRef.current;
  ringtoneRef.current = null;
  void ctx?.close().catch(() => undefined);
}

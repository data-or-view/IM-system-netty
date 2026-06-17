import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
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
  MessageContentType,
  SignalingAction,
  normalizeSignalingContent,
  parseMessageContent,
  type Message as SDKMessage,
  type SignalingActionName,
} from "im-sdk";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { useStore } from "@/store/store";
import { DEV_LIVEKIT_URL } from "@/config/runtime";

export type CallType = "voice" | "video";
export type CallPhase = "idle" | "outgoing" | "incoming" | "connecting" | "connected";
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
  const { state } = useStore();
  const [call, setCall] = useState<CallState>(EMPTY_CALL);
  const callRef = useRef<CallState>(EMPTY_CALL);
  const roomRef = useRef<Room | null>(null);
  const localAudioRef = useRef<LocalAudioTrack | null>(null);
  const localVideoRef = useRef<LocalVideoTrack | null>(null);
  const incomingTokenRef = useRef<string | null>(null);
  const liveKitUrlRef = useRef<string>(import.meta.env.VITE_LIVEKIT_URL ?? DEV_LIVEKIT_URL);
  const weakNetworkNotifiedRef = useRef(false);

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
    room.on(RoomEvent.Reconnecting, () => toast("媒体连接正在恢复..."));
    room.on(RoomEvent.Reconnected, () => toast("媒体连接已恢复"));
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
      peerConnectionTimeout: 20000,
      websocketTimeout: 15000,
      maxRetries: 3,
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
      phase: "outgoing",
      mode: "single",
      direction: "outgoing",
      callType,
      peer,
      muted: false,
      cameraOff: callType !== "video",
      remoteMedias: [],
    });

    try {
      const ack = await im.message.startCall({ toUserId: peer.userId, callType });
      liveKitUrlRef.current = ack.sfuEndpoint || liveKitUrlRef.current;
      setCall((prev) => ({ ...prev, phase: "connecting", roomId: ack.roomId }));
      await connectRoom(liveKitUrlRef.current, ack.token, callType);
      setCall((prev) => ({ ...prev, phase: "connected", startedAt: Date.now(), roomId: ack.roomId }));
    } catch (err) {
      console.error("start call failed:", err);
      toast(callErrorText(err, "发起通话失败"));
      await resetCall();
    }
  }, [connectRoom, resetCall]);

  const joinGroupCall = useCallback(async ({ group }: JoinGroupCallInput) => {
    if (!group.groupId || callRef.current.phase !== "idle") return;
    setCall({
      phase: "connecting",
      mode: "group",
      callType: "video",
      group,
      muted: false,
      cameraOff: false,
      remoteMedias: [],
    });
    try {
      const ack = await im.group.joinCall(group.groupId);
      const callType = ack.callType ?? "video";
      liveKitUrlRef.current = ack.sfuEndpoint || liveKitUrlRef.current;
      await connectRoom(liveKitUrlRef.current, ack.token, callType);
      setCall((prev) => ({
        ...prev,
        phase: "connected",
        mode: "group",
        callType,
        group,
        roomId: ack.roomId,
        startedAt: ack.startedAt ?? Date.now(),
        cameraOff: callType !== "video",
      }));
    } catch (err) {
      console.error("join group call failed:", err);
      toast(callErrorText(err, "加入群视频失败"));
      await resetCall();
    }
  }, [connectRoom, resetCall]);

  const startGroupCall = useCallback(async ({ group, callType = "video" }: StartGroupCallInput) => {
    if (!group.groupId || callRef.current.phase !== "idle") return;
    try {
      await im.group.startCall(group.groupId, callType);
      await joinGroupCall({ group });
    } catch (err) {
      console.error("start group call failed:", err);
      toast(callErrorText(err, "发起群视频失败"));
      await resetCall();
    }
  }, [joinGroupCall, resetCall]);

  const acceptCall = useCallback(async () => {
    const current = callRef.current;
    if (current.phase !== "incoming" || !current.peer?.userId || !current.roomId || !incomingTokenRef.current) return;

    try {
      setCall((prev) => ({ ...prev, phase: "connecting" }));
      await im.message.sendCallSignal(current.peer.userId, SignalingAction.ACCEPT, current.roomId);
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
      await im.message.sendCallSignal(current.peer.userId, SignalingAction.REJECT, current.roomId).catch(() => undefined);
    }
    await resetCall();
  }, [resetCall]);

  const cancelCall = useCallback(async () => {
    const current = callRef.current;
    if (current.peer?.userId && current.roomId) {
      await im.message.sendCallSignal(current.peer.userId, SignalingAction.CANCEL, current.roomId).catch(() => undefined);
    }
    await resetCall();
  }, [resetCall]);

  const hangupCall = useCallback(async () => {
    const current = callRef.current;
    const duration = current.startedAt ? Math.max(0, Math.floor((Date.now() - current.startedAt) / 1000)) : 0;
    if (current.mode === "group" && current.group?.groupId) {
      await im.group.leaveCall(current.group.groupId).catch(() => undefined);
    } else if (current.peer?.userId && current.roomId) {
      await im.message.sendCallSignal(current.peer.userId, SignalingAction.HANGUP, current.roomId, duration).catch(() => undefined);
    }
    await resetCall();
  }, [resetCall]);

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

  const handleSignalMessage = useCallback((msg: SDKMessage) => {
    if (msg.contentType !== MessageContentType.SIGNAL || msg.fromUserId === state.userId) return;

    const parsed = parseMessageContent(msg);
    if (parsed.type !== MessageContentType.SIGNAL) return;

    const signal = normalizeSignalingContent(parsed.content);
    if (!signal) return;

    if (signal.action === SignalingAction.CALLING) {
      if (!signal.roomId || !signal.token) return;
      incomingTokenRef.current = signal.token;
      liveKitUrlRef.current = signal.sfuEndpoint || liveKitUrlRef.current;
      setCall((prev) => {
        if (prev.phase !== "idle") return prev;
        return {
          phase: "incoming",
          mode: "single",
          direction: "incoming",
          callType: signal.callType ?? "voice",
          peer: resolvePeer(msg.fromUserId, state),
          roomId: signal.roomId,
          muted: false,
          cameraOff: signal.callType !== "video",
          remoteMedias: [],
        };
      });
      return;
    }

    const current = callRef.current;
    if (!signal.roomId || signal.roomId !== current.roomId) return;
    handleRemoteSignal(signal.action, resetCall);
  }, [resetCall, state]);

  useEffect(() => {
    const unsub = im.on("message", handleSignalMessage);
    return () => unsub();
  }, [handleSignalMessage]);

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
    toggleMute,
    toggleCamera,
  }), [acceptCall, call, cancelCall, hangupCall, joinGroupCall, rejectCall, startCall, startGroupCall, toggleCamera, toggleMute]);

  return <CallContext.Provider value={value}>{children}</CallContext.Provider>;
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

function handleRemoteSignal(action: SignalingActionName, resetCall: () => Promise<void>) {
  if (action === SignalingAction.REJECT) {
    toast("对方已拒绝");
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

function callErrorText(err: unknown, fallback: string): string {
  const text = err instanceof Error ? err.message : String(err ?? "");
  const lower = text.toLowerCase();
  if (lower.includes("permission") || lower.includes("notallowed") || lower.includes("denied")) {
    return "摄像头或麦克风权限不可用，请检查浏览器权限";
  }
  if (lower.includes("timeout") || lower.includes("websocket") || lower.includes("connect")) {
    return "媒体服务连接失败，请确认 LiveKit 已启动";
  }
  if (lower.includes("not found") || lower.includes("not_active") || lower.includes("inactive")) {
    return "通话已结束或对方尚未接入";
  }
  return fallback;
}

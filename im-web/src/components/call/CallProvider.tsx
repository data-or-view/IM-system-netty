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
  Room,
  RoomEvent,
  Track,
  createLocalAudioTrack,
  createLocalVideoTrack,
  type LocalAudioTrack,
  type LocalVideoTrack,
  type RemoteAudioTrack,
  type RemoteTrack,
  type RemoteTrackPublication,
  type RemoteVideoTrack,
  type RemoteParticipant,
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

export type CallType = "voice" | "video";
export type CallPhase = "idle" | "outgoing" | "incoming" | "connecting" | "connected";

export type CallPeer = {
  userId: string;
  name?: string;
  faceUrl?: string;
};

export type CallState = {
  phase: CallPhase;
  callType: CallType;
  direction?: "incoming" | "outgoing";
  peer?: CallPeer;
  roomId?: string;
  startedAt?: number;
  muted: boolean;
  cameraOff: boolean;
  localVideoTrack?: LocalVideoTrack;
  remoteAudioTrack?: RemoteAudioTrack;
  remoteVideoTrack?: RemoteVideoTrack;
};

type StartCallInput = {
  peer: CallPeer;
  callType: CallType;
};

type CallContextValue = {
  call: CallState;
  startCall: (input: StartCallInput) => Promise<void>;
  acceptCall: () => Promise<void>;
  rejectCall: () => Promise<void>;
  cancelCall: () => Promise<void>;
  hangupCall: () => Promise<void>;
  toggleMute: () => Promise<void>;
  toggleCamera: () => Promise<void>;
};

const DEFAULT_LIVEKIT_URL = "ws://localhost:7880";
const EMPTY_CALL: CallState = {
  phase: "idle",
  callType: "voice",
  muted: false,
  cameraOff: false,
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
  const liveKitUrlRef = useRef<string>(import.meta.env.VITE_LIVEKIT_URL ?? DEFAULT_LIVEKIT_URL);

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

    const room = new Room();
    roomRef.current = room;

    const handleTrackSubscribed = (
      track: RemoteTrack,
      _publication: RemoteTrackPublication,
      _participant: RemoteParticipant,
    ) => {
      if (track.kind === Track.Kind.Audio) {
        setCall((prev) => ({ ...prev, remoteAudioTrack: track as RemoteAudioTrack }));
      } else if (track.kind === Track.Kind.Video) {
        setCall((prev) => ({ ...prev, remoteVideoTrack: track as RemoteVideoTrack }));
      }
    };

    const handleTrackUnsubscribed = (track: RemoteTrack) => {
      if (track.kind === Track.Kind.Audio) {
        setCall((prev) => (
          prev.remoteAudioTrack === track ? { ...prev, remoteAudioTrack: undefined } : prev
        ));
      } else if (track.kind === Track.Kind.Video) {
        setCall((prev) => (
          prev.remoteVideoTrack === track ? { ...prev, remoteVideoTrack: undefined } : prev
        ));
      }
    };

    room.on(RoomEvent.TrackSubscribed, handleTrackSubscribed);
    room.on(RoomEvent.TrackUnsubscribed, handleTrackUnsubscribed);
    room.on(RoomEvent.Disconnected, () => {
      localAudioRef.current = null;
      localVideoRef.current = null;
      roomRef.current = null;
      setCall((prev) => (
        prev.phase === "idle"
          ? prev
          : { ...prev, localVideoTrack: undefined, remoteAudioTrack: undefined, remoteVideoTrack: undefined }
      ));
    });

    await room.connect(url, token);

    const audioTrack = await createLocalAudioTrack();
    localAudioRef.current = audioTrack;
    await room.localParticipant.publishTrack(audioTrack);

    if (nextCallType === "video") {
      const videoTrack = await createLocalVideoTrack();
      localVideoRef.current = videoTrack;
      await room.localParticipant.publishTrack(videoTrack);
      setCall((prev) => ({ ...prev, localVideoTrack: videoTrack }));
    }
  }, [disconnectCurrentRoom]);

  const startCall = useCallback(async ({ peer, callType }: StartCallInput) => {
    if (!peer.userId || callRef.current.phase !== "idle") return;

    setCall({
      phase: "outgoing",
      direction: "outgoing",
      callType,
      peer,
      muted: false,
      cameraOff: callType !== "video",
    });

    try {
      const ack = await im.message.startCall({ toUserId: peer.userId, callType });
      liveKitUrlRef.current = ack.sfuEndpoint || liveKitUrlRef.current;
      setCall((prev) => ({ ...prev, phase: "connecting", roomId: ack.roomId }));
      await connectRoom(liveKitUrlRef.current, ack.token, callType);
      setCall((prev) => ({ ...prev, phase: "connected", startedAt: Date.now(), roomId: ack.roomId }));
    } catch (err) {
      console.error("start call failed:", err);
      toast("发起通话失败");
      await resetCall();
    }
  }, [connectRoom, resetCall]);

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
      toast("接听失败");
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
    if (current.peer?.userId && current.roomId) {
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
      setCall((prev) => {
        if (prev.phase !== "idle") return prev;
        return {
          phase: "incoming",
          direction: "incoming",
          callType: signal.callType ?? "voice",
          peer: resolvePeer(msg.fromUserId, state),
          roomId: signal.roomId,
          muted: false,
          cameraOff: signal.callType !== "video",
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
    acceptCall,
    rejectCall,
    cancelCall,
    hangupCall,
    toggleMute,
    toggleCamera,
  }), [acceptCall, call, cancelCall, hangupCall, rejectCall, startCall, toggleCamera, toggleMute]);

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

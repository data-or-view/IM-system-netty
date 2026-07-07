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
  type LocalAudioTrack,
  type LocalVideoTrack,
} from "livekit-client";
import {
  SignalingAction,
  type CallSignalEvent,
  type SignalingActionName,
} from "im-sdk";
import { toast } from "sonner";
import { im } from "@/sdk/im-sdk";
import { useStore } from "@/store/store";
import { DEV_LIVEKIT_URL } from "@/config/runtime";
import { toOptimisticMessage } from "@/lib/messages";
import { startIncomingAttention, stopIncomingAttention } from "@/components/call/call-attention";
import { ensureMediaPermission } from "@/components/call/call-config";
import { callErrorText } from "@/components/call/call-errors";
import { EMPTY_CALL, type CallContextValue, type CallPeer, type CallState, type JoinGroupCallInput, type StartCallInput, type StartGroupCallInput } from "@/components/call/call-types";
import { useLiveKitRoom } from "@/components/call/useLiveKitRoom";

export type { CallState, RemoteMedia } from "@/components/call/call-types";

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

  const connectRoom = useLiveKitRoom({
    setCall,
    roomRef,
    localAudioRef,
    localVideoRef,
    weakNetworkNotifiedRef,
    disconnectCurrentRoom,
  });

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

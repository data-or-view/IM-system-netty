import { useCallback, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import {
  ConnectionQuality,
  Room,
  RoomEvent,
  Track,
  createLocalAudioTrack,
  createLocalVideoTrack,
  type LocalAudioTrack,
  type LocalVideoTrack,
  type Participant,
  type RemoteAudioTrack,
  type RemoteParticipant,
  type RemoteTrack,
  type RemoteTrackPublication,
  type RemoteVideoTrack,
} from "livekit-client";
import { toast } from "sonner";
import {
  AUDIO_CAPTURE_OPTIONS,
  AUDIO_PUBLISH_OPTIONS,
  LIVEKIT_ROOM_OPTIONS,
  VIDEO_CAPTURE_OPTIONS,
  VIDEO_PUBLISH_OPTIONS,
} from "@/components/call/call-config";
import type { CallState, CallType, RemoteMedia } from "@/components/call/call-types";

interface LiveKitRoomOptions {
  setCall: Dispatch<SetStateAction<CallState>>;
  roomRef: MutableRefObject<Room | null>;
  localAudioRef: MutableRefObject<LocalAudioTrack | null>;
  localVideoRef: MutableRefObject<LocalVideoTrack | null>;
  weakNetworkNotifiedRef: MutableRefObject<boolean>;
  disconnectCurrentRoom: () => Promise<void>;
}

export function useLiveKitRoom({
  setCall,
  roomRef,
  localAudioRef,
  localVideoRef,
  weakNetworkNotifiedRef,
  disconnectCurrentRoom,
}: LiveKitRoomOptions) {
  return useCallback(async (url: string, token: string, nextCallType: CallType) => {
    await disconnectCurrentRoom();

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
      websocketTimeout: 8000,
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
  }, [disconnectCurrentRoom, localAudioRef, localVideoRef, roomRef, setCall, weakNetworkNotifiedRef]);
}

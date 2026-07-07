import type {
  GroupCallParticipant,
} from "im-sdk";
import type {
  LocalVideoTrack,
  RemoteAudioTrack,
  RemoteVideoTrack,
} from "livekit-client";

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

export type StartCallInput = {
  peer: CallPeer;
  callType: CallType;
};

export type StartGroupCallInput = {
  group: GroupCallTarget;
  callType?: CallType;
};

export type JoinGroupCallInput = {
  group: GroupCallTarget;
  mediaPermissionChecked?: boolean;
  suppressFailureToast?: boolean;
};

export type CallContextValue = {
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

export const EMPTY_CALL: CallState = {
  phase: "idle",
  mode: "single",
  callType: "voice",
  muted: false,
  cameraOff: false,
  remoteMedias: [],
};

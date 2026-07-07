import {
  AudioPresets,
  Track,
  VideoPresets,
  type RoomOptions,
  type TrackPublishOptions,
} from "livekit-client";
import type { CallType } from "@/components/call/call-types";

export const AUDIO_CAPTURE_OPTIONS = {
  echoCancellation: true,
  noiseSuppression: true,
  autoGainControl: true,
  channelCount: 1,
} as const;

export const VIDEO_CAPTURE_OPTIONS = {
  resolution: VideoPresets.h540.resolution,
  frameRate: 24,
} as const;

export const AUDIO_PUBLISH_OPTIONS: TrackPublishOptions = {
  source: Track.Source.Microphone,
  audioPreset: AudioPresets.speech,
  dtx: true,
  red: true,
};

export const VIDEO_PUBLISH_OPTIONS: TrackPublishOptions = {
  source: Track.Source.Camera,
  videoEncoding: VideoPresets.h540.encoding,
  videoSimulcastLayers: [VideoPresets.h180, VideoPresets.h360],
  simulcast: true,
  degradationPreference: "maintain-framerate",
};

export const LIVEKIT_ROOM_OPTIONS: RoomOptions = {
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

export async function ensureMediaPermission(callType: CallType): Promise<void> {
  if (!navigator.mediaDevices?.getUserMedia) return;
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: AUDIO_CAPTURE_OPTIONS,
    video: callType === "video" ? VIDEO_CAPTURE_OPTIONS : false,
  });
  stream.getTracks().forEach((track) => track.stop());
}

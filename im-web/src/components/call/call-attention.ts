import type { MutableRefObject } from "react";

export function startIncomingAttention(
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

export function stopIncomingAttention(
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

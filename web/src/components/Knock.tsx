"use client";

import { useCallback, useEffect, useRef, useState } from "react";

// A "knock" is a real-time presence ritual: you tap a rhythm, each tap is
// relayed over the signaling socket, and the other person feels + sees the same
// taps land. It's a two-way "duet"; their knock-backs bloom on your stage too.
// The live rhythm stays WebSocket-only. The explicit Knock action can escalate
// to a call-style invitation so closed phones ring through the OS call UI.

// Synthesize a knuckle-on-door knock with WebAudio (no asset needed): a fast low
// "thud" body + a high-passed noise "click" attack. `pitch` adds gentle variation
// so a rhythm sounds musical rather than robotic.
export function playKnock(ctx: AudioContext | null, pitch = 1) {
  if (!ctx) return;
  const t = ctx.currentTime;

  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = "sine";
  osc.frequency.setValueAtTime(180 * pitch, t);
  osc.frequency.exponentialRampToValueAtTime(90 * pitch, t + 0.08);
  gain.gain.setValueAtTime(0.0001, t);
  gain.gain.exponentialRampToValueAtTime(0.6, t + 0.005);
  gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.18);
  osc.connect(gain);
  gain.connect(ctx.destination);
  osc.start(t);
  osc.stop(t + 0.2);

  const frames = Math.floor(ctx.sampleRate * 0.03);
  const buf = ctx.createBuffer(1, frames, ctx.sampleRate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < frames; i++) {
    data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / frames, 3);
  }
  const noise = ctx.createBufferSource();
  noise.buffer = buf;
  const ng = ctx.createGain();
  ng.gain.value = 0.25;
  const hp = ctx.createBiquadFilter();
  hp.type = "highpass";
  hp.frequency.value = 1200;
  noise.connect(hp);
  hp.connect(ng);
  ng.connect(ctx.destination);
  noise.start(t);
}

export function vibrateKnock() {
  if (typeof navigator !== "undefined" && typeof navigator.vibrate === "function") {
    // A quick double buzz per tap so each knock *feels* like a knock-knock.
    navigator.vibrate([25, 20, 35]);
  }
}

type Ripple = { id: number; x: number; y: number; mine: boolean };

// Full-screen immersive stage. Every pointer-down sends one knock (parent relays
// + plays sound) and blooms a ring where you touched. `theirPulse` increments
// when the other person knocks back, blooming their ring in a different color.
export function KnockSurface({
  name,
  theirPulse,
  onTap,
  onCall,
  onClose,
}: {
  name: string;
  theirPulse: number;
  onTap: () => void;
  onCall: () => void;
  onClose: () => void;
}) {
  const [ripples, setRipples] = useState<Ripple[]>([]);
  const [taps, setTaps] = useState(0);
  const idRef = useRef(0);
  const knockLabel = (() => {
    if (taps === 0) return "Knock anywhere";
    if (taps === 1) return "Knock";
    if (taps === 2) return "Knock knock";
    return "Knock knock knock";
  })();

  const spawn = useCallback((x: number, y: number, mine: boolean) => {
    const id = ++idRef.current;
    setRipples((r) => [...r.slice(-14), { id, x, y, mine }]);
    window.setTimeout(() => {
      setRipples((r) => r.filter((p) => p.id !== id));
    }, 950);
  }, []);

  // Their knock-backs bloom from a wandering spot around the center.
  useEffect(() => {
    if (theirPulse <= 0) return;
    spawn(34 + Math.random() * 32, 30 + Math.random() * 28, false);
  }, [theirPulse, spawn]);

  const handleTap = (e: React.PointerEvent) => {
    e.preventDefault();
    const rect = e.currentTarget.getBoundingClientRect();
    const x = ((e.clientX - rect.left) / rect.width) * 100;
    const y = ((e.clientY - rect.top) / rect.height) * 100;
    spawn(x, y, true);
    setTaps((n) => n + 1);
    onTap();
  };

  return (
    <div className="knock-stage fixed inset-0 z-[60] select-none text-white">
      {/* Knock anywhere on the stage. */}
      <div className="absolute inset-0 touch-none" onPointerDown={handleTap}>
        {ripples.map((r) => (
          <span
            key={r.id}
            className={`knock-ripple ${r.mine ? "mine" : "theirs"}`}
            style={{ left: `${r.x}%`, top: `${r.y}%` }}
          />
        ))}
        <div className="pointer-events-none absolute inset-0 grid place-items-center">
          <div className="text-center">
            <div className="relative mx-auto mb-7 h-28 w-28">
              {taps > 0 ? <span key={taps} className="knock-hit-ring" /> : null}
              <div className="knock-core absolute inset-0 grid place-items-center rounded-full border border-white/15 text-[40px]">
                ✊
              </div>
            </div>
            <p className="text-[11px] uppercase tracking-[0.22em] text-white/40">
              Knock knock knock
            </p>
            <h2 className="mt-1 text-[26px] font-light">{name}</h2>
            <p className="mt-2 text-[13px] text-white/40">{knockLabel}</p>
          </div>
        </div>
      </div>

      <button
        onClick={onClose}
        className="absolute right-5 top-5 rounded-full border border-white/15 px-4 py-2 text-[12px] text-white/70 transition-colors hover:border-white/40 hover:text-white"
      >
        Done
      </button>
      <div className="absolute inset-x-0 bottom-10 flex justify-center">
        <button
          onClick={onCall}
          className="rounded-full bg-white px-7 py-3 text-[14px] font-medium text-text transition-transform active:scale-95"
        >
          Knock {name}
        </button>
      </div>
    </div>
  );
}

// Immersive incoming overlay: a centered card that flashes with each received
// tap (in rhythm) and lets you knock back (→ enters the duet) or pick up.
export function KnockIncoming({
  pulseKey,
  onKnockBack,
  onCall,
  onDismiss,
}: {
  pulseKey: number;
  onKnockBack?: () => void;
  onCall?: () => void;
  onDismiss: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // Re-trigger the flash on every new tap.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.classList.remove("knock-flash");
    void el.offsetWidth;
    el.classList.add("knock-flash");
  }, [pulseKey]);

  return (
    <div className="fixed inset-0 z-[55] grid place-items-center bg-text/10 px-6 backdrop-blur-sm">
      <div
        ref={ref}
        className="w-full max-w-sm rounded-[16px] border border-hairline bg-white p-7 text-center shadow-[0_24px_90px_rgba(10,10,10,0.18)]"
      >
        <div className="knock-core mx-auto mb-4 grid h-20 w-20 place-items-center rounded-full border border-hairline text-[34px]">
          ✊
        </div>
        <p className="text-[11px] uppercase tracking-[0.22em] text-text-secondary">
          Knock knock knock
        </p>
        <h2 className="mt-1 text-[24px] font-light text-text">Someone&apos;s at the door</h2>
        <p className="mt-1 text-[13px] text-text-secondary">
          {onKnockBack && onCall ? "Answer to see who" : "An anonymous live rhythm"}
        </p>
        {onKnockBack && onCall ? (
          <div className="mt-6 grid grid-cols-2 gap-3">
            <button
              onClick={onKnockBack}
              className="rounded-[10px] border border-hairline py-3 text-[14px] text-text transition-colors hover:border-text/30"
            >
              Knock back
            </button>
            <button
              onClick={onCall}
              className="rounded-[10px] bg-text py-3 text-[14px] text-white transition-transform active:scale-95"
            >
              Call
            </button>
          </div>
        ) : null}
        <button
          onClick={onDismiss}
          className="mt-3 text-[12px] text-text-secondary transition-colors hover:text-text"
        >
          Dismiss
        </button>
      </div>
    </div>
  );
}

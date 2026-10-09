/**
 * The Back Office notification sounds ("relay baton" motifs, ADR 0032). Synthesised at run time
 * with the Web Audio API: no audio files, no samples, so they are original and licence-free.
 *
 * One timbre for the whole family: a soft FM mallet (sine carrier, sine modulator at 4x the pitch
 * whose index falls away within ~90 ms, like a mallet on a wooden bar), a quiet octave partial for
 * body, a 12 ms attack (no click) and an exponential decay, through a low-pass filter so nothing
 * is sharp. Every motif is shorter than 0.9 s.
 *
 *   turn    "Your turn": a warm rising perfect fourth (A4 -> D5) with a soft echo of the D5. The
 *           signature sound: the baton is handed to you.
 *   done    a light ascending D major arpeggio (D5 F#5 A5), quieter than the others.
 *   failed  a soft descending minor third in a low register (E4 -> C#4), darker filter, not an alarm.
 *   budget  a single muted double tap (G4 twice), heavily filtered.
 *
 * The parameters are data (`BATON_TIMBRE`, `MOTIFS`) so tests can assert them and the scheduled
 * nodes. `SoundPlayer` creates its `AudioContext` lazily, only after a user gesture (autoplay
 * policy), plays one sound at a time and at most one per `SOUND_MIN_GAP_MS` (bursts are coalesced
 * into one sound, the most important kind wins).
 */

export type SoundKind = 'turn' | 'done' | 'failed' | 'budget';
export const SOUND_KINDS: readonly SoundKind[] = ['turn', 'done', 'failed', 'budget'];

export interface Timbre {
  /** Modulator frequency / carrier frequency (4 = the strong 4th harmonic of a marimba bar). */
  modRatio: number;
  /** Modulation index at the strike (frequency deviation = index x modulator frequency). */
  modIndex: number;
  /** Seconds for the modulation index to fall to almost zero (the mallet transient). */
  modDecay: number;
  /** Seconds from silence to the peak of a note. */
  attack: number;
  /** A plain sine partial for body: frequency ratio and level relative to the note. */
  partialRatio: number;
  partialLevel: number;
  /** Low-pass filter of the motif. */
  lowpassHz: number;
  lowpassQ: number;
}

export interface Note {
  /** Start, seconds from the beginning of the motif. */
  at: number;
  /** Hz */
  freq: number;
  /** Seconds until the note has decayed to silence. */
  dur: number;
  /** Relative level, 0..1. */
  gain: number;
}

export interface Motif {
  kind: SoundKind;
  /** Overall level of the motif relative to the others, 0..1. */
  level: number;
  /** Overrides of the shared timbre. */
  timbre: Partial<Timbre>;
  notes: readonly Note[];
}

/** Peak gain of a note at full volume; several overlapping notes stay well below 1 (no clipping). */
export const PEAK_GAIN = 0.22;
/** Longest allowed motif (s). */
export const MAX_MOTIF_SECONDS = 0.9;
/** Minimum time between two sounds (ms); sounds in between are coalesced. */
export const SOUND_MIN_GAP_MS = 3000;

export const BATON_TIMBRE: Timbre = {
  modRatio: 4,
  modIndex: 1.1,
  modDecay: 0.09,
  attack: 0.012,
  partialRatio: 2,
  partialLevel: 0.12,
  lowpassHz: 2400,
  lowpassQ: 0.5,
};

// Equal temperament, A4 = 440 Hz.
export const NOTE_HZ = {
  'C#4': 277.18,
  E4: 329.63,
  G4: 392.0,
  A4: 440.0,
  D5: 587.33,
  'F#5': 739.99,
  A5: 880.0,
} as const;

export const MOTIFS: Record<SoundKind, Motif> = {
  turn: {
    kind: 'turn',
    level: 0.9,
    timbre: {},
    notes: [
      { at: 0, freq: NOTE_HZ.A4, dur: 0.34, gain: 0.8 },
      { at: 0.15, freq: NOTE_HZ.D5, dur: 0.46, gain: 1 },
      // the soft echo of the handoff
      { at: 0.42, freq: NOTE_HZ.D5, dur: 0.4, gain: 0.28 },
    ],
  },
  done: {
    kind: 'done',
    level: 0.55,
    timbre: { lowpassHz: 2800, modIndex: 0.9 },
    notes: [
      { at: 0, freq: NOTE_HZ.D5, dur: 0.3, gain: 0.7 },
      { at: 0.085, freq: NOTE_HZ['F#5'], dur: 0.3, gain: 0.75 },
      { at: 0.17, freq: NOTE_HZ.A5, dur: 0.38, gain: 0.85 },
    ],
  },
  failed: {
    kind: 'failed',
    level: 0.85,
    timbre: { lowpassHz: 1500, modIndex: 0.8, partialLevel: 0.08 },
    notes: [
      { at: 0, freq: NOTE_HZ.E4, dur: 0.4, gain: 0.9 },
      { at: 0.19, freq: NOTE_HZ['C#4'], dur: 0.52, gain: 1 },
    ],
  },
  budget: {
    kind: 'budget',
    level: 0.7,
    timbre: { lowpassHz: 1100, modIndex: 0.6, modDecay: 0.05 },
    notes: [
      { at: 0, freq: NOTE_HZ.G4, dur: 0.16, gain: 0.9 },
      { at: 0.12, freq: NOTE_HZ.G4, dur: 0.18, gain: 0.75 },
    ],
  },
};

/** When a burst is coalesced into one sound, the highest priority wins. */
export const SOUND_PRIORITY: Record<SoundKind, number> = { turn: 4, failed: 3, budget: 2, done: 1 };

export function timbreOf(m: Motif): Timbre {
  return { ...BATON_TIMBRE, ...m.timbre };
}

/** Length of a motif in seconds (end of its last note). */
export function motifDuration(m: Motif): number {
  return Math.max(...m.notes.map((n) => n.at + n.dur));
}

/** The part of the Web Audio API the scheduler uses (a real or an offline context, or a mock). */
export type AudioContextLike = Pick<
  BaseAudioContext,
  'currentTime' | 'destination' | 'createOscillator' | 'createGain' | 'createBiquadFilter'
>;

export interface ScheduledSound {
  /** Absolute context time at which the motif is silent. */
  end: number;
  /** Fades out within 30 ms and stops every oscillator. */
  stop(): void;
}

const SILENT = 0.0001;

/**
 * Schedules `motif` on `ctx` at `t0` (context time) into `destination` at `volume` (0..1).
 * Graph: per note [carrier sine <- FM modulator] + [partial sine] -> envelope -> low-pass -> master.
 */
export function scheduleMotif(
  ctx: AudioContextLike,
  destination: AudioNode,
  motif: Motif,
  volume: number,
  t0 = ctx.currentTime,
): ScheduledSound {
  const tb = timbreOf(motif);
  const master = ctx.createGain();
  master.gain.setValueAtTime(clamp01(volume) * motif.level * PEAK_GAIN, t0);
  const filter = ctx.createBiquadFilter();
  filter.type = 'lowpass';
  filter.frequency.setValueAtTime(tb.lowpassHz, t0);
  filter.Q.setValueAtTime(tb.lowpassQ, t0);
  filter.connect(master);
  master.connect(destination);

  const oscillators: OscillatorNode[] = [];
  let end = t0;
  for (const n of motif.notes) {
    const t = t0 + n.at;
    const stopAt = t + n.dur + 0.05;
    end = Math.max(end, t + n.dur);

    const env = ctx.createGain();
    env.gain.setValueAtTime(0, t);
    env.gain.linearRampToValueAtTime(n.gain, t + tb.attack);
    env.gain.exponentialRampToValueAtTime(SILENT, t + n.dur);
    env.connect(filter);

    const carrier = ctx.createOscillator();
    carrier.type = 'sine';
    carrier.frequency.setValueAtTime(n.freq, t);

    const modFreq = n.freq * tb.modRatio;
    const modulator = ctx.createOscillator();
    modulator.type = 'sine';
    modulator.frequency.setValueAtTime(modFreq, t);
    const modDepth = ctx.createGain();
    modDepth.gain.setValueAtTime(tb.modIndex * modFreq, t);
    modDepth.gain.exponentialRampToValueAtTime(SILENT, t + tb.modDecay);
    modulator.connect(modDepth);
    modDepth.connect(carrier.frequency);
    carrier.connect(env);

    const partial = ctx.createOscillator();
    partial.type = 'sine';
    partial.frequency.setValueAtTime(n.freq * tb.partialRatio, t);
    const partialGain = ctx.createGain();
    partialGain.gain.setValueAtTime(tb.partialLevel, t);
    partial.connect(partialGain);
    partialGain.connect(env);

    for (const o of [carrier, modulator, partial]) {
      o.start(t);
      o.stop(stopAt);
      oscillators.push(o);
    }
  }

  return {
    end,
    stop: () => {
      const now = ctx.currentTime;
      try {
        master.gain.cancelScheduledValues(now);
        master.gain.setValueAtTime(master.gain.value, now);
        master.gain.linearRampToValueAtTime(0, now + 0.03);
        for (const o of oscillators) o.stop(now + 0.04);
      } catch {
        // already stopped
      }
    },
  };
}

function clamp01(v: number): number {
  return Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : 0;
}

export type PlayResult = 'played' | 'queued' | 'muted' | 'locked';

export interface SoundPlayerOptions {
  /** Creates the context (default: `AudioContext`); returns null where Web Audio is missing. */
  createContext?: () =>
    (AudioContextLike & { state?: string; resume?: () => Promise<void> }) | null;
  /** Whether the page has had a user gesture (default: `navigator.userActivation`). */
  activated?: () => boolean;
  now?: () => number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/**
 * Plays the motifs. One sound at a time (a new one fades the previous out), at most one per
 * `SOUND_MIN_GAP_MS`: a request inside the gap is queued and played when the gap ends; several
 * queued requests are coalesced into one, the highest `SOUND_PRIORITY` wins.
 */
export class SoundPlayer {
  private ctx: (AudioContextLike & { state?: string; resume?: () => Promise<void> }) | null = null;
  private current: ScheduledSound | null = null;
  private lastAt = Number.NEGATIVE_INFINITY;
  private pending: { kind: SoundKind; volume: number } | null = null;
  private timer: unknown = null;
  private readonly o: Required<SoundPlayerOptions>;

  constructor(options: SoundPlayerOptions = {}) {
    this.o = {
      createContext: options.createContext ?? defaultContext,
      activated: options.activated ?? defaultActivated,
      now: options.now ?? (() => Date.now()),
      setTimer: options.setTimer ?? ((fn, ms) => setTimeout(fn, ms)),
      clearTimer: options.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>)),
    };
  }

  /** Call from a user gesture (a click on a toggle or a preview button): creates/resumes the context. */
  unlock(): boolean {
    return this.context(true) !== null;
  }

  /** Plays `kind` now or after the gap; `force` (previews) skips the gap but still stops the previous sound. */
  play(kind: SoundKind, volume: number, options: { force?: boolean } = {}): PlayResult {
    if (!(volume > 0)) return 'muted';
    const now = this.o.now();
    if (!options.force && now - this.lastAt < SOUND_MIN_GAP_MS) {
      if (!this.pending || SOUND_PRIORITY[kind] >= SOUND_PRIORITY[this.pending.kind])
        this.pending = { kind, volume };
      if (this.timer === null)
        this.timer = this.o.setTimer(() => this.flush(), this.lastAt + SOUND_MIN_GAP_MS - now);
      return 'queued';
    }
    if (options.force) this.cancelPending();
    return this.start(kind, volume, now);
  }

  dispose(): void {
    this.cancelPending();
    this.current?.stop();
    this.current = null;
  }

  private flush(): void {
    this.timer = null;
    const p = this.pending;
    this.pending = null;
    if (p) this.start(p.kind, p.volume, this.o.now());
  }

  private cancelPending(): void {
    if (this.timer !== null) this.o.clearTimer(this.timer);
    this.timer = null;
    this.pending = null;
  }

  private start(kind: SoundKind, volume: number, now: number): PlayResult {
    const ctx = this.context(false);
    if (!ctx) return 'locked';
    this.current?.stop();
    this.current = scheduleMotif(
      ctx,
      ctx.destination,
      MOTIFS[kind],
      volume,
      ctx.currentTime + 0.01,
    );
    this.lastAt = now;
    return 'played';
  }

  private context(fromGesture: boolean) {
    if (!this.ctx && (fromGesture || this.o.activated())) {
      try {
        this.ctx = this.o.createContext();
      } catch {
        this.ctx = null;
      }
    }
    if (this.ctx?.state === 'suspended') void this.ctx.resume?.().catch(() => undefined);
    return this.ctx;
  }
}

function defaultContext(): AudioContext | null {
  const Ctor =
    globalThis.AudioContext ??
    (globalThis as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  return Ctor ? new Ctor() : null;
}

function defaultActivated(): boolean {
  const ua = (globalThis.navigator as Navigator & { userActivation?: { hasBeenActive: boolean } })
    ?.userActivation;
  return ua ? ua.hasBeenActive : false;
}

let shared: SoundPlayer | null = null;

/** The one player of the page (previews and notifications share it, so they never overlap). */
export function soundPlayer(): SoundPlayer {
  shared ??= new SoundPlayer();
  return shared;
}

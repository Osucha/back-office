import {
  BATON_TIMBRE,
  MAX_MOTIF_SECONDS,
  MOTIFS,
  PEAK_GAIN,
  SOUND_KINDS,
  SOUND_MIN_GAP_MS,
  SoundPlayer,
  motifDuration,
  scheduleMotif,
  timbreOf,
  type AudioContextLike,
} from './sounds';

/** A recording stand-in for the Web Audio API (jsdom has none; OfflineAudioContext neither). */
class FakeParam {
  value = 0;
  readonly calls: [string, number, number][] = [];
  setValueAtTime(v: number, t: number) {
    this.calls.push(['set', v, t]);
    this.value = v;
    return this;
  }
  linearRampToValueAtTime(v: number, t: number) {
    this.calls.push(['linear', v, t]);
    return this;
  }
  exponentialRampToValueAtTime(v: number, t: number) {
    this.calls.push(['exp', v, t]);
    return this;
  }
  cancelScheduledValues(t: number) {
    this.calls.push(['cancel', 0, t]);
    return this;
  }
}

class FakeNode {
  readonly targets: unknown[] = [];
  connect(target: unknown) {
    this.targets.push(target);
    return target;
  }
}

class FakeOsc extends FakeNode {
  type = 'sine';
  readonly frequency = new FakeParam();
  readonly starts: number[] = [];
  readonly stops: number[] = [];
  start(t: number) {
    this.starts.push(t);
  }
  stop(t: number) {
    this.stops.push(t);
  }
}

class FakeGain extends FakeNode {
  readonly gain = new FakeParam();
}

class FakeFilter extends FakeNode {
  type = '';
  readonly frequency = new FakeParam();
  readonly Q = new FakeParam();
}

class FakeContext {
  currentTime = 10;
  state = 'running';
  readonly destination = new FakeNode();
  readonly oscillators: FakeOsc[] = [];
  readonly gains: FakeGain[] = [];
  readonly filters: FakeFilter[] = [];
  createOscillator() {
    const o = new FakeOsc();
    this.oscillators.push(o);
    return o;
  }
  createGain() {
    const g = new FakeGain();
    this.gains.push(g);
    return g;
  }
  createBiquadFilter() {
    const f = new FakeFilter();
    this.filters.push(f);
    return f;
  }
  resume() {
    this.state = 'running';
    return Promise.resolve();
  }
}

const asCtx = (c: FakeContext) => c as unknown as AudioContextLike;

describe('notification sounds', () => {
  it('keeps every motif short, soft and in a gentle register', () => {
    for (const kind of SOUND_KINDS) {
      const m = MOTIFS[kind];
      const tb = timbreOf(m);
      expect(motifDuration(m)).toBeLessThan(MAX_MOTIF_SECONDS);
      expect(tb.attack).toBeGreaterThanOrEqual(0.008); // no click
      expect(tb.lowpassHz).toBeLessThanOrEqual(3000); // no harsh highs
      for (const n of m.notes) {
        expect(n.freq).toBeGreaterThan(200);
        expect(n.freq).toBeLessThan(1000);
        expect(n.gain).toBeLessThanOrEqual(1);
      }
    }
  });

  it('designs the motif family as specified', () => {
    const ratio = (a: number, b: number) => Math.round(1200 * Math.log2(b / a)); // cents
    const turn = MOTIFS.turn.notes;
    expect(ratio(turn[0].freq, turn[1].freq)).toBe(500); // perfect fourth up
    expect(turn[2].freq).toBe(turn[1].freq); // the echo
    expect(turn[2].gain).toBeLessThan(0.5);

    const done = MOTIFS.done.notes;
    expect(done).toHaveLength(3);
    expect(done[0].freq < done[1].freq && done[1].freq < done[2].freq).toBe(true);
    expect(MOTIFS.done.level).toBeLessThan(MOTIFS.turn.level); // quieter

    const failed = MOTIFS.failed.notes;
    expect(ratio(failed[0].freq, failed[1].freq)).toBe(-300); // minor third down
    expect(Math.max(...failed.map((n) => n.freq))).toBeLessThan(400); // low register

    const budget = MOTIFS.budget.notes;
    expect(budget).toHaveLength(2);
    expect(budget[0].freq).toBe(budget[1].freq); // double tap
    expect(timbreOf(MOTIFS.budget).lowpassHz).toBeLessThan(BATON_TIMBRE.lowpassHz); // muted
  });

  it('schedules an FM voice per note with the envelope and filter of the data', () => {
    const ctx = new FakeContext();
    const sound = scheduleMotif(
      asCtx(ctx),
      ctx.destination as unknown as AudioNode,
      MOTIFS.turn,
      0.5,
      2,
    );
    // carrier + modulator + partial per note
    expect(ctx.oscillators).toHaveLength(MOTIFS.turn.notes.length * 3);
    const carriers = ctx.oscillators.filter((_, i) => i % 3 === 0);
    expect(carriers.map((o) => o.frequency.calls[0][1])).toEqual(
      MOTIFS.turn.notes.map((n) => n.freq),
    );
    expect(carriers.map((o) => o.starts[0])).toEqual(MOTIFS.turn.notes.map((n) => 2 + n.at));
    const modulators = ctx.oscillators.filter((_, i) => i % 3 === 1);
    expect(modulators[0].frequency.calls[0][1]).toBeCloseTo(440 * BATON_TIMBRE.modRatio);
    expect(modulators[0].targets[0]).toBeInstanceOf(FakeGain); // depth -> carrier.frequency

    expect(ctx.filters[0].type).toBe('lowpass');
    expect(ctx.filters[0].frequency.calls[0][1]).toBe(BATON_TIMBRE.lowpassHz);

    const master = ctx.gains[0];
    expect(master.gain.calls[0][1]).toBeCloseTo(0.5 * MOTIFS.turn.level * PEAK_GAIN);
    expect(master.targets).toContain(ctx.destination);

    // envelope of the first note: silent -> peak after the attack -> exponential decay to silence
    const env = ctx.gains[1].gain.calls;
    expect(env[0]).toEqual(['set', 0, 2]);
    expect(env[1][0]).toBe('linear');
    expect(env[1][2]).toBeCloseTo(2 + BATON_TIMBRE.attack);
    expect(env[2][0]).toBe('exp');
    expect(env[2][2]).toBeCloseTo(2 + MOTIFS.turn.notes[0].dur);

    expect(sound.end).toBeCloseTo(2 + motifDuration(MOTIFS.turn));
    for (const o of ctx.oscillators) expect(o.stops[0]).toBeLessThanOrEqual(sound.end + 0.06);

    ctx.currentTime = 2.1;
    sound.stop();
    expect(master.gain.calls.at(-1)).toEqual(['linear', 0, 2.1 + 0.03]);
  });

  describe('SoundPlayer', () => {
    let now = 0;
    let timers: { fn: () => void; ms: number }[] = [];
    let ctx: FakeContext;
    const player = (activated = true) =>
      new SoundPlayer({
        createContext: () => {
          ctx = new FakeContext();
          return asCtx(ctx) as never;
        },
        activated: () => activated,
        now: () => now,
        setTimer: (fn, ms) => {
          timers.push({ fn, ms });
          return timers.length;
        },
        clearTimer: () => (timers = []),
      });

    beforeEach(() => {
      now = 100_000;
      timers = [];
    });

    it('waits for a user gesture before creating the audio context', () => {
      const p = player(false);
      expect(p.play('turn', 0.6)).toBe('locked');
      expect(p.unlock()).toBe(true);
      expect(p.play('turn', 0.6)).toBe('played');
    });

    it('is silent at volume 0', () => {
      expect(player().play('turn', 0)).toBe('muted');
    });

    it('plays at most one sound per gap and coalesces a burst into the most important one', () => {
      const p = player();
      expect(p.play('done', 0.6)).toBe('played');
      now += 500;
      expect(p.play('done', 0.6)).toBe('queued');
      expect(p.play('turn', 0.6)).toBe('queued');
      expect(p.play('budget', 0.6)).toBe('queued');
      expect(timers).toHaveLength(1);
      expect(timers[0].ms).toBe(SOUND_MIN_GAP_MS - 500);
      const before = ctx.oscillators.length;
      now += SOUND_MIN_GAP_MS;
      timers[0].fn();
      const added = ctx.oscillators.slice(before);
      expect(added).toHaveLength(MOTIFS.turn.notes.length * 3);
      expect(added[0].frequency.calls[0][1]).toBe(MOTIFS.turn.notes[0].freq);
    });

    it('never overlaps: a forced preview fades the previous sound out', () => {
      const p = player();
      p.play('turn', 0.6);
      const firstMaster = ctx.gains[0];
      now += 100;
      expect(p.play('failed', 0.6, { force: true })).toBe('played');
      expect(firstMaster.gain.calls.some(([op, v]) => op === 'linear' && v === 0)).toBe(true);
    });
  });
});

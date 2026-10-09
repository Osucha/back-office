/**
 * Relay track model: one segment per visited workflow node, coloured by who held the baton at that
 * step, plus (for a run in flight) the likely path ahead, taken from the definition snapshot.
 */
import type {
  Money,
  NodeExecution,
  NodeKind,
  TokenUsage,
  WorkflowDefinition,
  WorkflowDefinitionInput,
  WorkflowRunSummary,
} from '../../api/types';
import { holderOfKind, type BatonHolder } from './baton';

export type SegmentState = 'active' | 'finished' | 'failed' | 'skipped' | 'ahead';

export interface TrackSegment {
  /** Stable key for `@for` (execution id, or `ahead:<nodeId>`). */
  key: string;
  nodeId: string;
  name: string;
  kind: NodeKind | null;
  holder: BatonHolder;
  state: SegmentState;
  /** The segment where the baton is now. */
  current: boolean;
  live: boolean;
  attempt: number;
  port: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  cost: Money | null;
  usage: TokenUsage | null;
}

export interface TrackSource {
  status?: WorkflowRunSummary['status'];
  currentNodeId?: string | null;
  waitingOn?: WorkflowRunSummary['waitingOn'];
  nodes?: NodeExecution[];
  definitionSnapshot?: Pick<WorkflowDefinition, 'nodes' | 'edges'> | WorkflowDefinitionInput | null;
}

const HIDDEN_KINDS = new Set<NodeKind>(['START', 'NOTE', 'END']);
const ACTIVE_RUN = new Set(['PENDING', 'RUNNING', 'WAITING']);
/** Ports that continue the "happy path", in order of preference. */
const FORWARD_PORTS = ['success', 'approve', 'pass', 'out', 'yes', 'ok', 'done', 'merged'];
const MAX_AHEAD = 12;

export interface BuildTrackOptions {
  /** Append the likely path ahead for runs in flight (default true). */
  ahead?: boolean;
  /** Clock for running durations (tests). */
  now?: number;
}

export function buildTrack(run: TrackSource, opts: BuildTrackOptions = {}): TrackSegment[] {
  const now = opts.now ?? Date.now();
  const active = ACTIVE_RUN.has(run.status ?? '');
  const def = run.definitionSnapshot ?? null;
  const defNodes = new Map((def?.nodes ?? []).map((n) => [n.id, n]));

  const executions = [...(run.nodes ?? [])]
    .map((n, i) => ({ n, i }))
    .sort((a, b) => {
      const ta = a.n.startedAt ? Date.parse(a.n.startedAt) : NaN;
      const tb = b.n.startedAt ? Date.parse(b.n.startedAt) : NaN;
      if (Number.isNaN(ta) || Number.isNaN(tb) || ta === tb) return a.i - b.i;
      return ta - tb;
    })
    .map((x) => x.n)
    .filter((n) => !HIDDEN_KINDS.has(n.kind ?? (defNodes.get(n.nodeId ?? '')?.kind as NodeKind)));

  const segments: TrackSegment[] = executions.map((n, i) => {
    const kind = n.kind ?? (defNodes.get(n.nodeId ?? '')?.kind as NodeKind | undefined) ?? null;
    const state = stateOf(n);
    const end = n.finishedAt ?? (state === 'active' ? new Date(now).toISOString() : null);
    return {
      key: n.id ?? `exec:${n.nodeId}:${i}`,
      nodeId: n.nodeId ?? '',
      name: n.nodeName || defNodes.get(n.nodeId ?? '')?.name || n.nodeId || 'step',
      kind,
      holder: state === 'failed' ? 'blocked' : state === 'skipped' ? 'idle' : holderOfKind(kind),
      state,
      current: false,
      live: state === 'active' && holderOfKind(kind) !== 'you',
      attempt: n.attempt ?? 1,
      port: n.port ?? null,
      startedAt: n.startedAt ?? null,
      finishedAt: n.finishedAt ?? null,
      durationMs:
        n.startedAt && end ? Math.max(0, Date.parse(end) - Date.parse(n.startedAt)) : null,
      cost: n.cost ?? null,
      usage: n.usage ?? null,
    };
  });

  if (!active) {
    // A finished run: the last failed step (if any) is where it stopped.
    const lastFailed = [...segments].reverse().find((s) => s.state === 'failed');
    if (lastFailed && run.status === 'FAILED') lastFailed.current = true;
    return segments;
  }

  // The current segment: the latest active execution of the current node, or a synthetic one.
  const currentId = run.currentNodeId ?? run.waitingOn?.nodeId ?? null;
  let current = [...segments]
    .reverse()
    .find((s) => s.state === 'active' && (!currentId || s.nodeId === currentId));
  if (!current && currentId) {
    const node = defNodes.get(currentId);
    const kind = (node?.kind as NodeKind | undefined) ?? null;
    if (!kind || !HIDDEN_KINDS.has(kind)) {
      current = {
        key: `current:${currentId}`,
        nodeId: currentId,
        name: node?.name || currentId,
        kind,
        holder: holderOfKind(kind),
        state: 'active',
        current: true,
        live: holderOfKind(kind) !== 'you',
        attempt: 1,
        port: null,
        startedAt: null,
        finishedAt: null,
        durationMs: null,
        cost: null,
        usage: null,
      };
      segments.push(current);
    }
  }
  if (current) current.current = true;

  if (opts.ahead !== false && def && currentId) {
    segments.push(...pathAhead(def, currentId, new Set(segments.map((s) => s.nodeId))));
  }
  return segments;
}

function stateOf(n: NodeExecution): SegmentState {
  switch (n.status) {
    case 'RUNNING':
    case 'WAITING':
      return 'active';
    case 'FAILED':
      return 'failed';
    case 'SKIPPED':
      return 'skipped';
    default:
      return 'finished';
  }
}

/** Follows the forward port from `fromId` until an END, a visited node or MAX_AHEAD steps. */
export function pathAhead(
  def: NonNullable<TrackSource['definitionSnapshot']>,
  fromId: string,
  visited: ReadonlySet<string>,
): TrackSegment[] {
  const nodes = new Map(def.nodes.map((n) => [n.id, n]));
  const out: TrackSegment[] = [];
  const seen = new Set<string>([fromId]);
  let at = fromId;
  for (let i = 0; i < MAX_AHEAD; i++) {
    const next = forwardEdge(def, at, nodes.get(at)?.decision?.defaultBranch ?? null);
    if (!next || seen.has(next)) break;
    seen.add(next);
    const node = nodes.get(next);
    if (!node || node.kind === 'END') break;
    at = next;
    if (node.kind === 'NOTE' || node.kind === 'START' || visited.has(next)) continue;
    out.push({
      key: `ahead:${next}`,
      nodeId: next,
      name: node.name || next,
      kind: node.kind,
      holder: holderOfKind(node.kind),
      state: 'ahead',
      current: false,
      live: false,
      attempt: 0,
      port: null,
      startedAt: null,
      finishedAt: null,
      durationMs: null,
      cost: null,
      usage: null,
    });
  }
  return out;
}

function forwardEdge(
  def: NonNullable<TrackSource['definitionSnapshot']>,
  from: string,
  defaultBranch: string | null,
): string | null {
  const edges = def.edges.filter((e) => e.from === from);
  if (!edges.length) return null;
  const preferred = [...FORWARD_PORTS, ...(defaultBranch ? [defaultBranch] : [])];
  for (const port of preferred) {
    const e = edges.find((x) => x.fromPort?.toLowerCase() === port);
    if (e) return e.to;
  }
  return edges[0].to;
}

/** Relative widths for a strip: square root of the duration so one long step does not dominate. */
export function segmentWeights(segments: TrackSegment[]): number[] {
  const known = segments.map((s) => s.durationMs).filter((d): d is number => d !== null && d > 0);
  const median = known.length ? known.sort((a, b) => a - b)[Math.floor(known.length / 2)] : 60_000;
  return segments.map((s) => {
    const d = s.durationMs ?? (s.state === 'ahead' ? median * 0.6 : median);
    return Math.max(1, Math.sqrt(Math.max(d, 1000) / 1000));
  });
}

/** Width limits of a segment in px: proportional between these, never stretched to fill. */
export const SEGMENT_WIDTH = {
  mini: { min: 12, max: 56, base: 6, perWeight: 3 },
  full: { min: 88, max: 240, base: 56, perWeight: 14 },
} as const;

/**
 * Preferred widths (px) of the segments: proportional to `segmentWeights` (square root of the
 * duration) between a minimum and a maximum, so a one-step run is one short segment, left-aligned,
 * instead of a full-width bar. The strip may still shrink segments when it runs out of room.
 */
export function segmentWidths(segments: TrackSegment[], variant: 'mini' | 'full'): number[] {
  const w = SEGMENT_WIDTH[variant];
  return segmentWeights(segments).map((weight) =>
    Math.round(Math.min(w.max, Math.max(w.min, w.base + weight * w.perWeight))),
  );
}

/**
 * Index of the segment the pointer marks: the current one, else (a finished run) the last step
 * that ran; -1 when nothing ran.
 */
export function pointerIndex(segments: TrackSegment[]): number {
  const current = segments.findIndex((s) => s.current);
  if (current >= 0) return current;
  for (let i = segments.length - 1; i >= 0; i--) if (segments[i].state !== 'ahead') return i;
  return -1;
}

/** Accessible description of one segment. */
export function segmentLabel(
  s: TrackSegment,
  formatDuration: (ms: number) => string,
  formatCost: (m: Money) => string,
): string {
  const who: Record<BatonHolder, string> = {
    agent: $localize`:@@core.track.who.agent:agent`,
    you: $localize`:@@core.track.who.you:person`,
    ci: $localize`:@@core.track.who.ci:CI`,
    done: $localize`:@@core.track.who.done:done`,
    blocked: $localize`:@@core.track.who.blocked:failed`,
    idle: $localize`:@@core.track.who.idle:idle`,
  };
  const state: Record<SegmentState, string> = {
    active: $localize`:@@core.track.state.active:in progress`,
    finished: $localize`:@@core.track.state.finished:finished`,
    failed: $localize`:@@core.track.state.failed:failed`,
    skipped: $localize`:@@core.track.state.skipped:skipped`,
    ahead: $localize`:@@core.track.state.ahead:not reached yet`,
  };
  const parts = [s.name, who[s.holder], state[s.state]];
  if (s.attempt > 1) parts.push($localize`:@@core.track.attempt:attempt ${s.attempt}:attempt:`);
  if (s.durationMs !== null) parts.push(formatDuration(s.durationMs));
  if (s.cost && s.cost.amount) parts.push(formatCost(s.cost));
  if (s.current) parts.push($localize`:@@core.track.current:current`);
  return parts.join(', ');
}

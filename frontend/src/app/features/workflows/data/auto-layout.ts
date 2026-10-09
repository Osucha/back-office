/**
 * Layered ("Sugiyama-style") auto-layout for workflow graphs, left to right:
 *
 * 1. Cycle removal: a depth-first search from START (children in port order, so `success` /
 *    `approve` / the first branch come first) marks back edges (revision loops); they are ignored
 *    for layering and drawn later as loops around the nodes.
 * 2. Layer assignment: longest path from START over the remaining DAG, so every forward edge points
 *    right and a node sits after all of its predecessors.
 * 3. Main path: from START follow the first forward port to an END. The main path owns row 0 (the
 *    top row), so it reads as one straight line; every other node goes to the branch rows below.
 * 4. Crossing reduction: within each layer the branch nodes are ordered by the barycentre of their
 *    neighbours' rows (a few down / up sweeps), then each gets the row closest to its predecessors
 *    that is still free in its layer.
 * 5. Coordinates: x from the real widths of the layers plus `xGap` (>= 80 px), y from the tallest
 *    node of each row plus `yGap` (>= 48 px). Unreachable nodes get rows below, notes the last row.
 *
 * Node sizes come from the canvas (measured) when available, otherwise from `estimateNodeSize`.
 */
import type { WorkflowDefinitionInput, WorkflowNode } from '../../../core/api/types';
import { portsFor, visiblePortsFor, type PaletteIndex } from './graph-model';

export interface Size {
  width: number;
  height: number;
}

export interface LayoutOptions {
  xGap: number;
  yGap: number;
  originX: number;
  originY: number;
  /** Measured node sizes (canvas); missing nodes use `estimateNodeSize`. */
  sizes?: ReadonlyMap<string, Size> | null;
  palette?: PaletteIndex | null;
}

export const DEFAULT_LAYOUT: LayoutOptions = { xGap: 96, yGap: 56, originX: 40, originY: 40 };

// ---------------------------------------------------------------- node sizes

/** Card widths; keep in sync with `.wf-node` in ../components/workflow-nodes.ts. */
export const NODE_WIDTH = 240;
export const COMPACT_NODE_WIDTH = 180;
const HEAD_H = 24;
const TITLE_H = 26;
const BODY_PAD = 8;
const LINE_H = 16;
const PORTS_PAD = 9;
const PORT_H = 22;

export function nodeWidth(node: Pick<WorkflowNode, 'kind'>): number {
  return node.kind === 'START' || node.kind === 'END' ? COMPACT_NODE_WIDTH : NODE_WIDTH;
}

/** Estimated rendered size of a node card (used before the canvas has measured it). */
export function estimateNodeSize(
  node: WorkflowNode,
  palette?: PaletteIndex | null,
  connectedPorts: ReadonlySet<string> = new Set(),
): Size {
  let body = 1;
  switch (node.kind) {
    case 'TASK': {
      const tpl = node.task?.templateKey ? palette?.tasks.get(node.task.templateKey) : undefined;
      const o = node.task?.overrides;
      const overridden = !!(
        o &&
        (o.modelProfileId ||
          o.promptAppend ||
          o.promptReplace ||
          o.systemPromptAppend ||
          o.addSkills?.length ||
          o.removeSkills?.length ||
          (o.config && Object.keys(o.config).length))
      );
      // Without the palette assume the interface row (library templates have one): too much room
      // is harmless, too little makes cards overlap.
      if (!palette?.tasks.size || tpl?.interfaces?.length || overridden) body += 1.25;
      break;
    }
    case 'DECISION': {
      const branches = node.decision?.branches ?? [];
      const d = node.decision?.defaultBranch;
      body = Math.max(1, branches.length + (d && !branches.some((b) => b.id === d) ? 1 : 0));
      break;
    }
    case 'HUMAN_GATE':
      body = (node.gate?.question?.length ?? 0) > 30 ? 2 : 1;
      break;
    case 'NOTE':
      body = Math.min(7, Math.max(1, Math.ceil((node.note?.text?.length ?? 0) / 32)));
      break;
  }
  const ports = visiblePortsFor(node, palette, connectedPorts).length;
  return {
    width: nodeWidth(node),
    height:
      HEAD_H + TITLE_H + body * LINE_H + BODY_PAD + (ports ? PORTS_PAD + ports * PORT_H + 1 : 0),
  };
}

function sizeOf(node: WorkflowNode, opts: LayoutOptions, connected: Map<string, Set<string>>) {
  return (
    opts.sizes?.get(node.id) ??
    estimateNodeSize(node, opts.palette, connected.get(node.id) ?? new Set())
  );
}

// ---------------------------------------------------------------- graph analysis

export interface LayeredGraph {
  /** nodeId -> layer (0 = START). Notes are absent. */
  layers: Map<string, number>;
  /** Edge ids that close a cycle (drawn as loops). */
  backEdges: Set<string>;
  /** Node ids of the main path, START first. */
  mainPath: string[];
  /** Executable nodes not reachable from START. */
  unreachable: Set<string>;
}

/** Out edges of a node in port order (known ports first, in the order the node declares them). */
function orderedOut(def: WorkflowDefinitionInput, palette?: PaletteIndex | null) {
  const byId = new Map(def.nodes.map((n) => [n.id, n]));
  const out = new Map<string, WorkflowDefinitionInput['edges']>();
  for (const e of def.edges) {
    if (!byId.has(e.from) || !byId.has(e.to)) continue;
    if (!out.has(e.from)) out.set(e.from, []);
    out.get(e.from)!.push(e);
  }
  for (const [id, list] of out) {
    const ports = portsFor(byId.get(id)!, palette).map((p) => p.toLowerCase());
    const rank = (p: string) => {
      const i = ports.indexOf(p.toLowerCase());
      return i < 0 ? ports.length : i;
    };
    list.sort((a, b) => rank(a.fromPort) - rank(b.fromPort));
  }
  return out;
}

export function layerGraph(
  def: WorkflowDefinitionInput,
  palette?: PaletteIndex | null,
): LayeredGraph {
  const exec = def.nodes.filter((n) => n.kind !== 'NOTE');
  const execIds = new Set(exec.map((n) => n.id));
  const out = orderedOut(def, palette);
  const start = exec.find((n) => n.kind === 'START');
  const { backEdges, reachable } = removeCycles(exec, execIds, out, start?.id);
  const forward = def.edges.filter(
    (e) => execIds.has(e.from) && execIds.has(e.to) && !backEdges.has(e.id),
  );
  const layers = longestPathLayers(exec, forward);
  const mainPath = start ? followFirstPorts(start.id, out, backEdges, execIds) : [];
  const unreachable = new Set(exec.filter((n) => !reachable.has(n.id)).map((n) => n.id));
  return { layers, backEdges, mainPath, unreachable };
}

type OutEdges = Map<string, WorkflowDefinitionInput['edges']>;

function append<K, V>(map: Map<K, V[]>, key: K, value: V): void {
  const list = map.get(key);
  if (list) list.push(value);
  else map.set(key, [value]);
}

/**
 * 1. Cycle removal by DFS (iterative, children in port order), from START first so its loops are
 * the ones broken; returns the back edges and the nodes reachable from START.
 */
function removeCycles(
  exec: readonly WorkflowNode[],
  execIds: ReadonlySet<string>,
  out: OutEdges,
  startId: string | undefined,
): { backEdges: Set<string>; reachable: Set<string> } {
  const walk: CycleWalk = {
    out,
    execIds,
    state: new Map(),
    backEdges: new Set(),
    reachable: new Set(),
  };
  if (startId) depthFirst(walk, startId, true);
  for (const n of exec) if (!walk.state.has(n.id)) depthFirst(walk, n.id, false);
  return { backEdges: walk.backEdges, reachable: walk.reachable };
}

interface CycleWalk {
  out: OutEdges;
  execIds: ReadonlySet<string>;
  state: Map<string, 'open' | 'done'>;
  backEdges: Set<string>;
  reachable: Set<string>;
}

/** Iterative DFS from `root`: an edge to an open node closes a cycle (a back edge). */
function depthFirst(w: CycleWalk, root: string, markReachable: boolean): void {
  const stack: Array<{ id: string; i: number }> = [];
  const visit = (id: string) => {
    w.state.set(id, 'open');
    if (markReachable) w.reachable.add(id);
    stack.push({ id, i: 0 });
  };
  visit(root);
  while (stack.length) {
    const top = stack[stack.length - 1];
    const edges = (w.out.get(top.id) ?? []).filter((e) => w.execIds.has(e.to));
    if (top.i >= edges.length) {
      w.state.set(top.id, 'done');
      stack.pop();
      continue;
    }
    const e = edges[top.i++];
    const s = w.state.get(e.to);
    if (e.to === e.from || s === 'open') w.backEdges.add(e.id);
    else if (!s) visit(e.to);
  }
}

/** 2. Longest-path layering over the DAG of forward edges (Kahn's topological order). */
function longestPathLayers(
  exec: readonly WorkflowNode[],
  forward: WorkflowDefinitionInput['edges'],
): Map<string, number> {
  const indeg = new Map(exec.map((n) => [n.id, 0]));
  for (const e of forward) indeg.set(e.to, (indeg.get(e.to) ?? 0) + 1);
  const layers = new Map<string, number>();
  const queue = exec.filter((n) => indeg.get(n.id) === 0).map((n) => n.id);
  for (const id of queue) layers.set(id, 0);
  const fwdOut = new Map<string, string[]>();
  for (const e of forward) append(fwdOut, e.from, e.to);
  while (queue.length) {
    const id = queue.shift()!;
    for (const next of fwdOut.get(id) ?? []) {
      layers.set(next, Math.max(layers.get(next) ?? 0, (layers.get(id) ?? 0) + 1));
      indeg.set(next, (indeg.get(next) ?? 1) - 1);
      if (indeg.get(next) === 0) queue.push(next);
    }
  }
  return layers;
}

/** 3. Main path: follow the first forward port from START. */
function followFirstPorts(
  startId: string,
  out: OutEdges,
  backEdges: ReadonlySet<string>,
  execIds: ReadonlySet<string>,
): string[] {
  const mainPath: string[] = [];
  const seen = new Set<string>();
  let at: string | undefined = startId;
  while (at && !seen.has(at)) {
    seen.add(at);
    mainPath.push(at);
    at = (out.get(at) ?? []).find(
      (e) => !backEdges.has(e.id) && execIds.has(e.to) && !seen.has(e.to),
    )?.to;
  }
  return mainPath;
}

/** Kept for callers of the old BFS layout: `nodeId -> layer` of the reachable nodes. */
export function bfsLevels(def: WorkflowDefinitionInput): Map<string, number> {
  const g = layerGraph(def);
  const out = new Map<string, number>();
  if (!def.nodes.some((n) => n.kind === 'START')) return out;
  for (const [id, l] of g.layers) if (!g.unreachable.has(id)) out.set(id, l);
  return out;
}

// ---------------------------------------------------------------- layout

export function autoLayout(
  def: WorkflowDefinitionInput,
  options: Partial<LayoutOptions> = {},
): WorkflowDefinitionInput {
  const opts: LayoutOptions = { ...DEFAULT_LAYOUT, ...options };
  const g = layerGraph(def, opts.palette);
  const byId = new Map(def.nodes.map((n) => [n.id, n]));
  const order = new Map(def.nodes.map((n, i) => [n.id, i]));
  const connected = new Map<string, Set<string>>();
  for (const e of def.edges) {
    if (!connected.has(e.from)) connected.set(e.from, new Set());
    connected.get(e.from)!.add(e.fromPort);
  }

  // Rows: main path in row 0, the reachable rest from row 1, unreachable nodes below them.
  const row = new Map<string, number>();
  for (const id of g.mainPath) row.set(id, 0);
  const reachableRest = [...g.layers.keys()].filter((id) => !row.has(id) && !g.unreachable.has(id));
  const { preds, succs } = forwardNeighbours(def, g);
  const byLayer = (ids: string[]) => groupByLayer(ids, g.layers, order);
  assignRows(byLayer(reachableRest), row, preds, succs, 1);
  const firstFree = Math.max(0, ...row.values()) + 1;
  assignRows(byLayer([...g.unreachable]), row, preds, succs, firstFree);

  // Coordinates.
  const exec = def.nodes.filter((n) => n.kind !== 'NOTE');
  const sizes = new Map(exec.map((n) => [n.id, sizeOf(n, opts, connected)]));
  const maxLayer = Math.max(0, ...g.layers.values());
  const layerWidth = new Array<number>(maxLayer + 1).fill(0);
  for (const n of exec) {
    const l = g.layers.get(n.id) ?? 0;
    layerWidth[l] = Math.max(layerWidth[l], sizes.get(n.id)!.width);
  }
  const layerX = offsets(layerWidth, opts.originX, opts.xGap);
  const maxRow = Math.max(0, ...row.values());
  const rowHeight = new Array<number>(maxRow + 1).fill(0);
  for (const n of exec) {
    const r = row.get(n.id) ?? 0;
    rowHeight[r] = Math.max(rowHeight[r], sizes.get(n.id)!.height);
  }
  const rowY = offsets(rowHeight, opts.originY, opts.yGap);
  const y = rowY[maxRow + 1];

  const positioned = new Map<string, { x: number; y: number }>();
  for (const n of exec) {
    const l = g.layers.get(n.id) ?? 0;
    const w = sizes.get(n.id)!.width;
    // Narrower cards are centred in their layer so edges between layers keep their length.
    positioned.set(n.id, {
      x: Math.round(layerX[l] + (layerWidth[l] - w) / 2),
      y: Math.round(rowY[row.get(n.id) ?? 0]),
    });
  }
  // Notes: one row under everything, left to right.
  let noteX = opts.originX;
  for (const n of def.nodes.filter((d) => d.kind === 'NOTE')) {
    const s = sizeOf(n, opts, connected);
    positioned.set(n.id, { x: noteX, y: Math.round(y) });
    noteX += s.width + opts.xGap / 2;
  }
  return {
    ...def,
    nodes: def.nodes.map((n) => ({
      ...n,
      position: positioned.get(n.id) ?? byId.get(n.id)!.position,
    })),
  };
}

/** Predecessors and successors over the forward edges between layered nodes. */
function forwardNeighbours(
  def: WorkflowDefinitionInput,
  g: LayeredGraph,
): { preds: Map<string, string[]>; succs: Map<string, string[]> } {
  const preds = new Map<string, string[]>();
  const succs = new Map<string, string[]>();
  for (const e of def.edges) {
    if (g.backEdges.has(e.id) || !g.layers.has(e.from) || !g.layers.has(e.to)) continue;
    append(preds, e.to, e.from);
    append(succs, e.from, e.to);
  }
  return { preds, succs };
}

/** `[layer, ids]` by ascending layer; ids in definition order within a layer. */
function groupByLayer(
  ids: readonly string[],
  layers: ReadonlyMap<string, number>,
  order: ReadonlyMap<string, number>,
): Array<[number, string[]]> {
  const m = new Map<number, string[]>();
  for (const id of ids) append(m, layers.get(id) ?? 0, id);
  for (const list of m.values()) list.sort((a, b) => order.get(a)! - order.get(b)!);
  return [...m.entries()].sort((a, b) => a[0] - b[0]);
}

/**
 * Start coordinate of each band (layer column or row) from `origin`, one `gap` after every band
 * that has content; one extra entry = the coordinate after the last band.
 */
function offsets(extents: readonly number[], origin: number, gap: number): number[] {
  const out: number[] = [];
  let at = origin;
  for (const extent of extents) {
    out.push(at);
    if (extent) at += extent + gap;
  }
  out.push(at);
  return out;
}

/**
 * Orders the nodes of each layer by the barycentre of their neighbours (down and up sweeps) and
 * gives each the free row nearest to the median row of its predecessors, never above `minRow`.
 */
function assignRows(
  layers: Array<[number, string[]]>,
  row: Map<string, number>,
  preds: Map<string, string[]>,
  succs: Map<string, string[]>,
  minRow: number,
): void {
  if (!layers.length) return;
  const rank = new Map<string, number>();
  layers.forEach(([, ids]) => ids.forEach((id, i) => rank.set(id, minRow + i)));
  const pos = (id: string) => row.get(id) ?? rank.get(id);
  const bary = (ids: string[] | undefined) => {
    const known = (ids ?? []).map(pos).filter((v): v is number => v !== undefined);
    return known.length ? known.reduce((a, b) => a + b, 0) / known.length : null;
  };
  const sweep = (list: Array<[number, string[]]>, neighbours: Map<string, string[]>) => {
    for (const [, ids] of list) {
      const keyed = ids.map((id, i) => ({ id, k: bary(neighbours.get(id)) ?? rank.get(id)!, i }));
      keyed.sort((a, b) => a.k - b.k || a.i - b.i);
      keyed.forEach((e, i) => {
        ids[i] = e.id;
        rank.set(e.id, minRow + i);
      });
    }
  };
  for (let pass = 0; pass < 3; pass++) {
    sweep(layers, preds);
    sweep([...layers].reverse(), succs);
  }
  sweep(layers, preds);

  for (const [, ids] of layers) {
    let prev = minRow - 1;
    for (const id of ids) {
      const ps = (preds.get(id) ?? []).map(pos).filter((v): v is number => v !== undefined);
      ps.sort((a, b) => a - b);
      const median = ps.length ? ps[Math.floor((ps.length - 1) / 2)] : minRow;
      const r = Math.max(prev + 1, minRow, median);
      row.set(id, r);
      prev = r;
    }
  }
}

// ---------------------------------------------------------------- when to lay out

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Rectangles of the executable nodes and notes at their stored positions. */
export function nodeRects(
  def: WorkflowDefinitionInput,
  sizes?: ReadonlyMap<string, Size> | null,
  palette?: PaletteIndex | null,
): Map<string, Rect> {
  const connected = new Map<string, Set<string>>();
  for (const e of def.edges) {
    if (!connected.has(e.from)) connected.set(e.from, new Set());
    connected.get(e.from)!.add(e.fromPort);
  }
  const out = new Map<string, Rect>();
  for (const n of def.nodes) {
    const s = sizes?.get(n.id) ?? estimateNodeSize(n, palette, connected.get(n.id) ?? new Set());
    out.set(n.id, { x: n.position?.x ?? 0, y: n.position?.y ?? 0, ...s });
  }
  return out;
}

/**
 * True when the graph needs a layout: several nodes and none has a position, or two node cards
 * overlap (with a small margin) at their stored positions.
 */
export function needsLayout(
  def: WorkflowDefinitionInput,
  sizes?: ReadonlyMap<string, Size> | null,
  palette?: PaletteIndex | null,
): boolean {
  if (def.nodes.length < 2) return false;
  if (def.nodes.every((n) => !n.position || (n.position.x === 0 && n.position.y === 0)))
    return true;
  const rects = [...nodeRects(def, sizes, palette).values()];
  const margin = 8;
  for (let i = 0; i < rects.length; i++)
    for (let j = i + 1; j < rects.length; j++) {
      const a = rects[i];
      const b = rects[j];
      if (
        a.x < b.x + b.width + margin &&
        b.x < a.x + a.width + margin &&
        a.y < b.y + b.height + margin &&
        b.y < a.y + a.height + margin
      )
        return true;
    }
  return false;
}

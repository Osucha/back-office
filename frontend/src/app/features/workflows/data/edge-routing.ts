/**
 * Orthogonal edge routing on the canvas. Edges leave an output port to the right and enter the
 * input handle from the left, as horizontal / vertical segments with rounded bends, and avoid the
 * other node cards where a route exists:
 *
 * - forward edges try a straight line, then one vertical jog in the gap between the cards
 *   ("Z" route), then a detour along a free horizontal lane above or below the cards in the way;
 * - back edges (revision loops, the target is left of the source) always use a lane: out of the
 *   port, up or down to a free lane above or below every card between target and source, across,
 *   and into the target. Several loops over the same stretch get their own lane (`laneIndex`).
 *
 * Works on live positions, so manual layouts are routed as well as auto-laid ones.
 */
import type { Point } from '../../../core/api/types';
import type { Rect } from './auto-layout';

export interface Route {
  points: Point[];
  path: string;
  /** Where the edge label sits (middle of the route). */
  label: Point;
}

/** Horizontal stub out of the port and into the handle. */
const STUB = 18;
/** Clearance kept around cards. */
const CLEAR = 12;
/** Distance between stacked loop lanes. */
const LANE_STEP = 12;
const RADIUS = 8;

export function routeEdge(
  source: Point,
  target: Point,
  obstacles: Rect[],
  opts: { sourceRect?: Rect | null; targetRect?: Rect | null; laneIndex?: number } = {},
): Route {
  const blocks = obstacles.map((r) => inflate(r, CLEAR));
  // The source and target cards may be touched by the stubs, not crossed by the rest.
  const ends = [opts.sourceRect, opts.targetRect].filter((r): r is Rect => !!r);
  const free = (pts: Point[]) => segmentsFree(pts, blocks) && segmentsFree(pts.slice(1, -1), ends);
  const x1 = source.x + STUB;
  const x2 = target.x - STUB;
  const lane = opts.laneIndex ?? 0;

  const forward = x2 - x1 >= 0 ? forwardRoute(source, target, x1, x2, blocks, free) : null;
  if (forward) return forward;

  // A detour along a horizontal lane between the two stubs.
  const lo = Math.min(x1, x2);
  const hi = Math.max(x1, x2);
  const inRange = [...blocks, ...ends.map((e) => inflate(e, CLEAR))].filter(
    (b) => b.x < hi && b.x + b.width > lo,
  );
  const candidates = new Set<number>();
  const top = Math.min(source.y, target.y, ...inRange.map((b) => b.y));
  const bottom = Math.max(source.y, target.y, ...inRange.map((b) => b.y + b.height));
  const offset = lane * LANE_STEP;
  candidates.add(top - 8 - offset);
  candidates.add(bottom + 8 + offset);
  for (const b of inRange) {
    candidates.add(b.y - 8 - offset);
    candidates.add(b.y + b.height + 8 + offset);
  }
  if (x2 - x1 >= 0) {
    candidates.add(source.y);
    candidates.add(target.y);
  }
  const cost = (y: number) => Math.abs(y - source.y) + Math.abs(y - target.y);
  const sorted = [...candidates].sort((a, b) => cost(a) - cost(b));
  for (const y of sorted) {
    const pts = lanePoints(source, target, x1, x2, y);
    if (free(pts)) return build(pts);
  }
  // Nothing is free (dense manual layout): go around everything below.
  return build(lanePoints(source, target, x1, x2, bottom + 8 + offset));
}

/** A straight line, or one vertical jog in a gap (the middle first, then next to each obstacle). */
function forwardRoute(
  source: Point,
  target: Point,
  x1: number,
  x2: number,
  blocks: readonly Rect[],
  free: (pts: Point[]) => boolean,
): Route | null {
  if (Math.abs(source.y - target.y) < 1 && free([source, target])) return build([source, target]);
  const mid = (x1 + x2) / 2;
  const xs = [mid];
  for (const b of blocks) xs.push(b.x - 4, b.x + b.width + 4);
  const jogs = xs
    .filter((x) => x >= x1 && x <= x2)
    .sort((a, b) => Math.abs(a - mid) - Math.abs(b - mid));
  for (const x of jogs) {
    const pts = [source, { x, y: source.y }, { x, y: target.y }, target];
    if (free(pts)) return build(pts);
  }
  return null;
}

/** Out of the port to `x1`, along the horizontal lane at `y` to `x2`, into the target. */
function lanePoints(source: Point, target: Point, x1: number, x2: number, y: number): Point[] {
  return [
    source,
    { x: x1, y: source.y },
    { x: x1, y },
    { x: x2, y },
    { x: x2, y: target.y },
    target,
  ];
}

function inflate(r: Rect, by: number): Rect {
  return { x: r.x - by, y: r.y - by, width: r.width + 2 * by, height: r.height + 2 * by };
}

/** True when no axis-aligned segment of the polyline passes through the inside of a rectangle. */
export function segmentsFree(points: Point[], rects: Rect[]): boolean {
  for (let i = 0; i + 1 < points.length; i++) {
    const a = points[i];
    const b = points[i + 1];
    for (const r of rects) if (crosses(a, b, r)) return false;
  }
  return true;
}

function crosses(a: Point, b: Point, r: Rect): boolean {
  const left = r.x;
  const right = r.x + r.width;
  const topY = r.y;
  const bottomY = r.y + r.height;
  const minX = Math.min(a.x, b.x);
  const maxX = Math.max(a.x, b.x);
  const minY = Math.min(a.y, b.y);
  const maxY = Math.max(a.y, b.y);
  // Open intervals: touching an edge of the rectangle is allowed.
  return minX < right && maxX > left && minY < bottomY && maxY > topY;
}

/** Drops zero-length and collinear points and draws rounded bends. */
function build(raw: Point[]): Route {
  const pts: Point[] = [];
  for (const p of raw) {
    const last = pts[pts.length - 1];
    if (last && Math.abs(last.x - p.x) < 0.5 && Math.abs(last.y - p.y) < 0.5) continue;
    pts.push(p);
  }
  const simplified: Point[] = [];
  for (let i = 0; i < pts.length; i++) {
    const prev = simplified[simplified.length - 1];
    const next = pts[i + 1];
    if (
      prev &&
      next &&
      ((Math.abs(prev.x - pts[i].x) < 0.5 && Math.abs(pts[i].x - next.x) < 0.5) ||
        (Math.abs(prev.y - pts[i].y) < 0.5 && Math.abs(pts[i].y - next.y) < 0.5))
    )
      continue;
    simplified.push(pts[i]);
  }
  let d = `M${r(simplified[0].x)},${r(simplified[0].y)}`;
  for (let i = 1; i < simplified.length; i++) {
    const p = simplified[i];
    const prev = simplified[i - 1];
    const next = simplified[i + 1];
    if (!next) {
      d += ` L${r(p.x)},${r(p.y)}`;
      continue;
    }
    const rad = Math.min(RADIUS, dist(prev, p) / 2, dist(p, next) / 2);
    const inPt = towards(p, prev, rad);
    const outPt = towards(p, next, rad);
    d += ` L${r(inPt.x)},${r(inPt.y)} Q${r(p.x)},${r(p.y)} ${r(outPt.x)},${r(outPt.y)}`;
  }
  return { points: simplified, path: d, label: midpoint(simplified) };
}

const r = (v: number) => Math.round(v * 10) / 10;
const dist = (a: Point, b: Point) => Math.hypot(a.x - b.x, a.y - b.y);

function towards(from: Point, to: Point, by: number): Point {
  const d = dist(from, to) || 1;
  return { x: from.x + ((to.x - from.x) / d) * by, y: from.y + ((to.y - from.y) / d) * by };
}

/** The point halfway along the polyline, on its longest horizontal run when it has one. */
function midpoint(pts: Point[]): Point {
  let best: { a: Point; b: Point; len: number } | null = null;
  for (let i = 0; i + 1 < pts.length; i++) {
    const a = pts[i];
    const b = pts[i + 1];
    if (Math.abs(a.y - b.y) > 0.5) continue;
    const len = Math.abs(a.x - b.x);
    if (!best || len > best.len) best = { a, b, len };
  }
  if (best && best.len >= 60) return { x: (best.a.x + best.b.x) / 2, y: best.a.y };
  const total = pts.slice(1).reduce((s, p, i) => s + dist(pts[i], p), 0);
  let walked = 0;
  for (let i = 0; i + 1 < pts.length; i++) {
    const seg = dist(pts[i], pts[i + 1]);
    if (walked + seg >= total / 2) return towards(pts[i], pts[i + 1], total / 2 - walked);
    walked += seg;
  }
  return pts[0];
}

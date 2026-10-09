/**
 * Keyboard navigation on the canvas: arrow keys move the selection along the graph.
 * → follows the first connected output port, ← goes back to a predecessor (the nearest one on the
 * left), ↑ / ↓ move to the nearest node above / below.
 */
import type { WorkflowDefinitionInput } from '../../../core/api/types';
import type { Rect } from './auto-layout';
import { portsFor } from './graph-model';

export type NavDirection = 'left' | 'right' | 'up' | 'down';

export function neighbourInDirection(
  def: WorkflowDefinitionInput,
  fromId: string,
  dir: NavDirection,
  rects: ReadonlyMap<string, Rect>,
): string | null {
  const node = def.nodes.find((n) => n.id === fromId);
  const at = rects.get(fromId);
  if (!node || !at) return null;
  const centre = (r: Rect) => ({ x: r.x + r.width / 2, y: r.y + r.height / 2 });
  const c = centre(at);

  if (dir === 'right') {
    const ports = portsFor(node).map((p) => p.toLowerCase());
    const rank = (p: string) => {
      const i = ports.indexOf(p.toLowerCase());
      return i < 0 ? ports.length : i;
    };
    const out = def.edges
      .filter((e) => e.from === fromId && e.to !== fromId && rects.has(e.to))
      .sort((a, b) => rank(a.fromPort) - rank(b.fromPort));
    // Prefer a target on the right (forward) over a loop back.
    const forward = out.find((e) => centre(rects.get(e.to)!).x > c.x);
    return (forward ?? out[0])?.to ?? null;
  }
  if (dir === 'left') {
    const incoming = def.edges
      .filter((e) => e.to === fromId && e.from !== fromId && rects.has(e.from))
      .map((e) => ({ id: e.from, p: centre(rects.get(e.from)!) }));
    const onLeft = incoming.filter((i) => i.p.x < c.x);
    const pool = onLeft.length ? onLeft : incoming;
    pool.sort(
      (a, b) =>
        Math.abs(a.p.y - c.y) - Math.abs(b.p.y - c.y) ||
        Math.abs(a.p.x - c.x) - Math.abs(b.p.x - c.x),
    );
    return pool[0]?.id ?? null;
  }
  const sign = dir === 'down' ? 1 : -1;
  let best: { id: string; score: number } | null = null;
  for (const [id, r] of rects) {
    if (id === fromId) continue;
    const p = centre(r);
    const dy = (p.y - c.y) * sign;
    if (dy <= 1) continue;
    const score = dy + 1.5 * Math.abs(p.x - c.x);
    if (!best || score < best.score) best = { id, score };
  }
  return best?.id ?? null;
}

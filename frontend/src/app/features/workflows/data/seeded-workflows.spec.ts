import type { WorkflowDefinitionInput } from '../../../core/api/types';
import { autoLayout, layerGraph, needsLayout, nodeRects } from './auto-layout';
import { indexPalette } from './graph-model';
import { SEEDED_PALETTE, SEEDED_WORKFLOWS } from './seeded-workflows.fixture';
import { validateLocally } from './workflow-validation';

const palette = indexPalette(SEEDED_PALETTE);

/** Positions the seed used before the layered layout: 200 px apart, narrower than a card. */
function withOldSeedPositions(def: WorkflowDefinitionInput): WorkflowDefinitionInput {
  return {
    ...def,
    nodes: def.nodes.map((n, i) => ({ ...n, position: { x: i * 200, y: 200 } })),
  };
}

describe('seeded workflows (regression: the designer opened "Issue to reviewed MR" with 4 errors)', () => {
  for (const make of SEEDED_WORKFLOWS) {
    const def = make();

    it(`${def.name}: validates without errors or warnings`, () => {
      const r = validateLocally(def, palette);
      expect(r.errors).toEqual([]);
      expect(r.warnings).toEqual([]);
    });

    it(`${def.name}: old overlapping positions are detected and laid out without overlaps`, () => {
      const old = withOldSeedPositions(def);
      expect(needsLayout(old, null, palette)).toBe(true);
      const laid = autoLayout(old, { palette });
      expect(needsLayout(laid, null, palette)).toBe(false);
      expect(validateLocally(laid, palette).errors).toEqual([]);
    });

    it(`${def.name}: main path on one row, branches below, gaps of at least 80 / 48 px`, () => {
      const laid = autoLayout(def, { palette });
      const g = layerGraph(laid, palette);
      const rects = nodeRects(laid, null, palette);
      const main = g.mainPath.map((id) => rects.get(id)!);
      expect(new Set(main.map((r) => r.y)).size).toBe(1);
      const mainY = main[0].y;
      for (const [id, r] of rects) if (!g.mainPath.includes(id)) expect(r.y).toBeGreaterThan(mainY);
      for (let i = 1; i < main.length; i++)
        expect(main[i].x - (main[i - 1].x + main[i - 1].width)).toBeGreaterThanOrEqual(80);
      const all = [...rects.values()];
      for (const a of all)
        for (const b of all) {
          if (a === b) continue;
          const apartX = a.x + a.width + 80 <= b.x || b.x + b.width + 80 <= a.x;
          const apartY = a.y + a.height + 48 <= b.y || b.y + b.height + 48 <= a.y;
          expect(apartX || apartY).toBe(true);
        }
    });

    it(`${def.name}: stores the positions of the layered layout (backend seed in sync)`, () => {
      const laid = autoLayout(def, { palette });
      const pos = (d: WorkflowDefinitionInput) =>
        Object.fromEntries(d.nodes.map((n) => [n.id, [n.position.x, n.position.y]]));
      expect(pos(def)).toEqual(pos(laid));
      expect(needsLayout(def, null, palette)).toBe(false);
    });
  }

  it('detects the revision loops as back edges', () => {
    const g = layerGraph(SEEDED_WORKFLOWS[0](), palette);
    expect([...g.backEdges].sort()).toEqual(['e16', 'e18', 'e8']);
    expect(g.mainPath[0]).toBe('start');
    expect(g.mainPath[g.mainPath.length - 1]).toBe('done');
  });
});

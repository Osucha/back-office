import type { WorkflowDefinitionInput, WorkflowNode } from '../../../core/api/types';
import {
  REGION_LABEL_SPACE,
  REGION_PADDING,
  applyWorkspaces,
  membersOf,
  newWorkspace,
  stepWorkspace,
  withStepWorkspace,
  workspaceProblems,
  workspaceRegions,
} from './workspaces';

const task = (id: string, config?: Record<string, unknown>): WorkflowNode =>
  ({ id, name: id, kind: 'TASK', position: { x: 0, y: 0 }, task: { templateKey: 't', overrides: config ? { config } : {} } }) as WorkflowNode;

const def = (nodes: WorkflowNode[]): WorkflowDefinitionInput => ({
  name: 'w',
  nodes,
  edges: [],
  enabled: true,
  workspaces: [
    { key: 'main', name: 'Main', sources: [], roles: ['implementer', 'reviewer'] },
    { key: 'docs', name: 'Docs', sources: [], roles: [] },
  ],
});

describe('workflow workspaces (ADR 0044)', () => {
  it('reads and writes the step settings, dropping role and prepare without a workspace', () => {
    expect(stepWorkspace({ workspace: 'main', sessionRole: 'reviewer' })).toEqual({ workspace: 'main', sessionRole: 'reviewer', prepareOnly: false });
    expect(withStepWorkspace({ push: true }, { workspace: 'main', sessionRole: 'implementer' })).toEqual({
      push: true, workspace: 'main', sessionRole: 'implementer',
    });
    expect(withStepWorkspace({ workspace: 'main', sessionRole: 'x' }, { workspace: null })).toBeUndefined();
    // a prepare step has no session
    expect(withStepWorkspace({ workspace: 'main', sessionRole: 'x' }, { prepareOnly: true })).toEqual({ workspace: 'main', prepareOnly: true });
  });

  it('renaming or removing a workspace follows in its steps', () => {
    const d = def([task('a', { workspace: 'main', sessionRole: 'implementer' }), task('b', { workspace: 'docs' }), task('c')]);
    expect(membersOf(d, 'main')).toEqual(['a']);
    const renamed = applyWorkspaces(d, [{ ...d.workspaces![0], key: 'app' }, d.workspaces![1]], { main: 'app' });
    expect(stepWorkspace(renamed.nodes[0].task!.overrides!.config).workspace).toBe('app');
    expect(stepWorkspace(renamed.nodes[0].task!.overrides!.config).sessionRole).toBe('implementer');
    const removed = applyWorkspaces(d, [d.workspaces![0]], { docs: null });
    expect(removed.nodes[1].task!.overrides!.config).toBeUndefined();
    expect(removed.workspaces).toHaveLength(1);
  });

  it('suggests free keys and reports invalid ones', () => {
    expect(newWorkspace([]).key).toBe('main');
    expect(newWorkspace([{ key: 'main' }]).key).toBe('ws-2');
    expect(workspaceProblems({ key: 'Main' }, [])).toHaveLength(1);
    expect(workspaceProblems({ key: 'a' }, [{ key: 'a' }, { key: 'a' }])).toHaveLength(1);
    expect(workspaceProblems({ key: 'a', roles: ['Bad Role'] }, [{ key: 'a' }])).toHaveLength(1);
  });

  it('draws one region around the steps of each used workspace', () => {
    const d = def([task('a', { workspace: 'main' }), task('b', { workspace: 'main' }), task('c')]);
    const rects = new Map([
      ['a', { x: 100, y: 100, width: 200, height: 80 }],
      ['b', { x: 400, y: 300, width: 200, height: 80 }],
      ['c', { x: 900, y: 100, width: 200, height: 80 }],
    ]);
    const [main, ...rest] = workspaceRegions(d, rects);
    expect(rest).toEqual([]); // docs has no steps
    expect(main.nodeIds).toEqual(['a', 'b']);
    expect(main.name).toBe('Main');
    expect(main.x).toBe(100 - REGION_PADDING);
    expect(main.y).toBe(100 - REGION_PADDING - REGION_LABEL_SPACE);
    expect(main.width).toBe(500 + 2 * REGION_PADDING);
    expect(main.height).toBe(280 + 2 * REGION_PADDING + REGION_LABEL_SPACE);
  });
});

import type { ProjectWorkspace, WorkflowDefinitionInput, WorkflowNode } from '../../../core/api/types';
import { workspaceProblems } from '../../../core/ui/workspaces';
import {
  REGION_LABEL_SPACE,
  REGION_PADDING,
  membersOf,
  stepWorkspace,
  withStepWorkspace,
  workspaceRegions,
} from './workspaces';

const task = (id: string, config?: Record<string, unknown>): WorkflowNode =>
  ({ id, name: id, kind: 'TASK', position: { x: 0, y: 0 }, task: { templateKey: 't', overrides: config ? { config } : {} } }) as WorkflowNode;

const def = (nodes: WorkflowNode[]): WorkflowDefinitionInput => ({ name: 'w', nodes, edges: [], enabled: true });

const ws = (key: string, name = key): ProjectWorkspace =>
  ({ id: key, projectId: 'p', key, name, sources: [], roles: [], createdAt: '', updatedAt: '' }) as ProjectWorkspace;

describe('workspaces in the designer (ADR 0044)', () => {
  it('reads and writes the step settings, dropping role and prepare without a workspace', () => {
    expect(stepWorkspace({ workspace: 'main', sessionRole: 'reviewer' })).toEqual({ workspace: 'main', sessionRole: 'reviewer', prepareOnly: false });
    expect(withStepWorkspace({ push: true }, { workspace: 'main', sessionRole: 'implementer' })).toEqual({
      push: true, workspace: 'main', sessionRole: 'implementer',
    });
    expect(withStepWorkspace({ workspace: 'main', sessionRole: 'x' }, { workspace: null })).toBeUndefined();
    // a prepare step has no session
    expect(withStepWorkspace({ workspace: 'main', sessionRole: 'x' }, { prepareOnly: true })).toEqual({ workspace: 'main', prepareOnly: true });
  });

  it('reports invalid keys, duplicates and roles', () => {
    expect(workspaceProblems({ key: 'Main' }, [])).toHaveLength(1);
    expect(workspaceProblems({ key: 'a' }, [{ key: 'a' }])).toHaveLength(1);
    expect(workspaceProblems({ key: 'a', roles: ['Bad Role'] }, [])).toHaveLength(1);
    expect(workspaceProblems({ key: 'a', roles: ['reviewer'] }, [{ key: 'b' }])).toEqual([]);
  });

  it('draws one region around the steps of each workspace the workflow uses', () => {
    const d = def([task('a', { workspace: 'main' }), task('b', { workspace: 'main' }), task('c'), task('d', { workspace: 'gone' })]);
    expect(membersOf(d, 'main')).toEqual(['a', 'b']);
    const rects = new Map([
      ['a', { x: 100, y: 100, width: 200, height: 80 }],
      ['b', { x: 400, y: 300, width: 200, height: 80 }],
      ['c', { x: 900, y: 100, width: 200, height: 80 }],
      ['d', { x: 900, y: 400, width: 200, height: 80 }],
    ]);
    const [main, gone] = workspaceRegions(d, rects, [ws('docs'), ws('main', 'Main')]);
    expect(main.nodeIds).toEqual(['a', 'b']);
    expect(main.name).toBe('Main');
    expect(main.x).toBe(100 - REGION_PADDING);
    expect(main.y).toBe(100 - REGION_PADDING - REGION_LABEL_SPACE);
    expect(main.width).toBe(500 + 2 * REGION_PADDING);
    expect(main.height).toBe(280 + 2 * REGION_PADDING + REGION_LABEL_SPACE);
    // a key the project no longer has is still drawn, by its key
    expect(gone.name).toBe('gone');
  });
});

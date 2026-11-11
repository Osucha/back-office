import type { Agent } from '../../../core/api/types';
import { importableAgents, projectAgentRows } from './project-agents';

const agent = (key: string, name: string, projectId?: string): Agent =>
  ({
    id: `${key}-${projectId ?? 'g'}`,
    key,
    name,
    scope: projectId ? { type: 'PROJECT', projectId } : { type: 'GLOBAL' },
  }) as Agent;

describe('project agents (ADR 0060)', () => {
  const agents = [
    agent('reviewer', 'Reviewer'),
    agent('coder', 'Coder'),
    agent('coder', 'Coder (P1)', 'p1'),
    agent('docs', 'Doc writer', 'p1'),
    agent('other', 'Other project agent', 'p2'),
  ];

  it('lists the project agents first and marks replaced global ones', () => {
    const rows = projectAgentRows(agents, 'p1');
    expect(rows.map((r) => [r.agent.name, r.own, r.replacesGlobal, r.replaced])).toEqual([
      ['Coder (P1)', true, true, false],
      ['Doc writer', true, false, false],
      ['Coder', false, false, true],
      ['Reviewer', false, false, false],
    ]);
  });

  it('offers only global agents the project has no copy of', () => {
    expect(importableAgents(agents, 'p1').map((a) => a.key)).toEqual(['reviewer']);
    expect(importableAgents(agents, 'p2').map((a) => a.key)).toEqual(['coder', 'reviewer']);
  });
});

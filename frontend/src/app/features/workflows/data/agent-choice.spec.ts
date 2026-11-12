import type { Agent } from '../../../core/api/types';
import { coveredSkills, defaultStepAgentKey, pickAgent, visibleAgents } from './agent-choice';

const global = { type: 'GLOBAL' as const };
const p1 = { type: 'PROJECT' as const, projectId: 'p1' };

describe('agent pre-selection (ADR 0061)', () => {
  const agents: Agent[] = [
    { key: 'model-answer', name: 'Model answer', scope: global, builtIn: true },
    { key: 'analyst', name: 'Analyst', scope: global, builtIn: true, skills: ['jira'] },
    { key: 'reviewer', name: 'Reviewer', scope: global, builtIn: true, skills: ['code-review'] },
    { key: 'opencode', name: 'OpenCode', scope: global, builtIn: true, harness: 'OPENCODE' },
    {
      key: 'claude-code',
      name: 'Claude Code',
      scope: global,
      builtIn: true,
      harness: 'CLAUDE_CODE',
    },
    {
      key: 'kotlin-coder',
      name: 'Kotlin coder',
      scope: global,
      harness: 'CLAUDE_CODE',
      skills: ['kotlin', 'gradle'],
    },
  ];

  it('runs a step without an agent with opencode or the model answer', () => {
    expect(defaultStepAgentKey(true)).toBe('opencode');
    expect(defaultStepAgentKey(false)).toBe('model-answer');
    expect(defaultStepAgentKey(undefined)).toBe('model-answer');
  });

  it('picks the default agent of the kind when no skill is required', () => {
    expect(pickAgent({ needsCodingAgent: true }, agents)?.key).toBe('opencode');
    expect(pickAgent({ needsCodingAgent: false }, agents)?.key).toBe('model-answer');
    expect(pickAgent(null, agents)?.key).toBe('model-answer');
  });

  it('picks a coding agent for a template that needs one, with the most required skills', () => {
    expect(pickAgent({ needsCodingAgent: true, requiresSkills: ['kotlin'] }, agents)?.key).toBe(
      'kotlin-coder',
    );
    // a model answer agent with the skill is never picked for a coding template
    expect(
      pickAgent({ needsCodingAgent: true, requiresSkills: ['code-review'] }, agents)?.key,
    ).toBe('opencode');
  });

  it('prefers model answer agents for other templates, but skills come first', () => {
    expect(
      pickAgent({ needsCodingAgent: false, requiresSkills: ['code-review'] }, agents)?.key,
    ).toBe('reviewer');
    expect(pickAgent({ needsCodingAgent: false, requiresSkills: ['kotlin'] }, agents)?.key).toBe(
      'kotlin-coder',
    );
  });

  it('breaks ties built-in first, then by name', () => {
    const tied: Agent[] = [
      { key: 'zed', name: 'Zed', scope: global, harness: 'OPENCODE', skills: ['x'] },
      { key: 'bob', name: 'Bob', scope: global, harness: 'OPENCODE', skills: ['x'] },
      { key: 'ann', name: 'Ann', scope: global, harness: 'OPENCODE', builtIn: true, skills: ['x'] },
    ];
    expect(pickAgent({ needsCodingAgent: true, requiresSkills: ['x'] }, tied)?.key).toBe('ann');
    expect(
      pickAgent({ needsCodingAgent: true, requiresSkills: ['x'] }, tied.slice(0, 2))?.key,
    ).toBe('bob');
  });

  it('uses the project copy of an agent and is null when nothing fits', () => {
    const withCopy: Agent[] = [
      ...agents,
      {
        key: 'opencode',
        name: 'OpenCode (P1)',
        scope: p1,
        harness: 'OPENCODE',
        skills: ['kotlin'],
      },
    ];
    expect(visibleAgents(withCopy).filter((a) => a.key === 'opencode')).toHaveLength(1);
    expect(pickAgent({ needsCodingAgent: true, requiresSkills: ['kotlin'] }, withCopy)?.name).toBe(
      'OpenCode (P1)',
    );
    expect(pickAgent({ needsCodingAgent: true }, agents.slice(0, 3))).toBeNull();
    expect(pickAgent({ needsCodingAgent: false }, [])).toBeNull();
    expect(coveredSkills({ skills: ['a', 'b'] }, ['b', 'c'])).toBe(1);
  });
});

import type { Agent } from '../../../core/api/types';
import { effectiveStepAgent, stepModelUse, stepModelUseKey } from './model-step';

describe('stepModelUse (ADR 0050, 0056)', () => {
  it('maps a model answer agent to one gateway call and no agent to null', () => {
    expect(stepModelUse({ harness: null })).toEqual({ stepKind: 'LLM_CALL', harness: null });
    expect(stepModelUse({})).toEqual({ stepKind: 'LLM_CALL', harness: null });
    expect(stepModelUse(null)).toBeNull();
  });

  it('runs a coding agent with the agent harness', () => {
    expect(stepModelUse({ harness: 'OPENCODE' })).toEqual({
      stepKind: 'CODING_AGENT',
      harness: 'OPENCODE',
    });
    expect(stepModelUse({ harness: 'CLAUDE_CODE' })?.harness).toBe('CLAUDE_CODE');
  });

  it('a coding step that only prepares the run directory uses no model', () => {
    expect(stepModelUse({ harness: 'OPENCODE' }, { prepareOnly: true })).toBeNull();
    expect(stepModelUse({ harness: 'OPENCODE' }, null, { prepareOnly: 'true' })).toBeNull();
    expect(
      stepModelUse({ harness: 'OPENCODE' }, { prepareOnly: false }, { prepareOnly: true }),
    ).not.toBeNull();
  });

  it('builds a cache key per step kind and harness', () => {
    expect(stepModelUseKey({ stepKind: 'LLM_CALL', harness: null })).toBe('LLM_CALL');
    expect(stepModelUseKey({ stepKind: 'CODING_AGENT', harness: 'CLAUDE_CODE' })).toBe(
      'CODING_AGENT:CLAUDE_CODE',
    );
  });
});

describe('effectiveStepAgent (ADR 0056)', () => {
  const agents: Agent[] = [
    { key: 'claude-code', name: 'Claude Code', scope: { type: 'GLOBAL' }, harness: 'CLAUDE_CODE' },
    { key: 'coder', name: 'Global coder', scope: { type: 'GLOBAL' }, harness: 'OPENCODE' },
    {
      key: 'coder',
      name: 'Project coder',
      scope: { type: 'PROJECT', projectId: 'p1' },
      harness: 'CLAUDE_CODE',
    },
  ];
  const all: Agent[] = [
    ...agents,
    { key: 'model-answer', name: 'Model answer', scope: { type: 'GLOBAL' } },
    { key: 'opencode', name: 'OpenCode', scope: { type: 'GLOBAL' }, harness: 'OPENCODE' },
  ];
  const task = { needsCodingAgent: false };

  it('takes the step agent, else the default for what the template needs (ADR 0061)', () => {
    expect(effectiveStepAgent(task, 'claude-code', all)?.harness).toBe('CLAUDE_CODE');
    expect(effectiveStepAgent(task, null, all)?.key).toBe('model-answer');
    expect(effectiveStepAgent({ needsCodingAgent: true }, null, all)?.key).toBe('opencode');
    expect(stepModelUse(effectiveStepAgent(task, 'claude-code', all))?.stepKind).toBe(
      'CODING_AGENT',
    );
    expect(stepModelUse(effectiveStepAgent(task, null, all))?.stepKind).toBe('LLM_CALL');
  });

  it('prefers the project agent of a key and is null for an unknown one', () => {
    expect(effectiveStepAgent(task, 'coder', agents)?.name).toBe('Project coder');
    expect(effectiveStepAgent(task, 'missing', agents)).toBeNull();
    expect(effectiveStepAgent(null, null, agents)).toBeNull();
  });
});

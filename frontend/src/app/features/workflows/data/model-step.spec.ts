import { stepModelUse, stepModelUseKey } from './model-step';

const schema = (agentDefault?: string) => ({
  properties: {
    agent: { type: 'string', enum: ['OPENCODE', 'CLAUDE_CODE'], default: agentDefault },
  },
});

describe('stepModelUse (ADR 0050)', () => {
  it('maps LLM to one gateway call and steps without a model to null', () => {
    expect(stepModelUse({ executor: 'LLM' })).toEqual({ stepKind: 'LLM_CALL', harness: null });
    expect(stepModelUse({ executor: 'FETCH_ISSUE' })).toBeNull();
    expect(stepModelUse(null)).toBeNull();
    expect(stepModelUse({})).toBeNull();
  });

  it('takes the harness of a coding step from the node, the template, the schema default', () => {
    const implement = { executor: 'IMPLEMENT' as const, configSchema: schema('OPENCODE') };
    expect(stepModelUse(implement)).toEqual({ stepKind: 'CODING_AGENT', harness: 'OPENCODE' });
    expect(stepModelUse(implement, null, { agent: 'claude_code' })?.harness).toBe('CLAUDE_CODE');
    expect(stepModelUse(implement, { agent: 'OPENCODE' }, { agent: 'CLAUDE_CODE' })?.harness).toBe(
      'OPENCODE',
    );
    expect(
      stepModelUse({ executor: 'SPEC_KIT', configSchema: schema('CLAUDE_CODE') })?.harness,
    ).toBe('CLAUDE_CODE');
    expect(stepModelUse({ executor: 'SPEC_KIT' }, { agent: 'other' })?.harness).toBe('OPENCODE');
  });

  it('a step that only prepares the workspace uses no model', () => {
    expect(stepModelUse({ executor: 'IMPLEMENT' }, { prepareOnly: true })).toBeNull();
    expect(stepModelUse({ executor: 'IMPLEMENT' }, null, { prepareOnly: 'true' })).toBeNull();
  });

  it('builds a cache key per step kind and harness', () => {
    expect(stepModelUseKey({ stepKind: 'LLM_CALL', harness: null })).toBe('LLM_CALL');
    expect(stepModelUseKey({ stepKind: 'CODING_AGENT', harness: 'CLAUDE_CODE' })).toBe(
      'CODING_AGENT:CLAUDE_CODE',
    );
  });
});

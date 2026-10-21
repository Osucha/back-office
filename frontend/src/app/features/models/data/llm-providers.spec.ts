import { connectionProblems, emptyConnection, isSubscriptionToken } from './llm-providers';

describe('llm provider connections', () => {
  const anthropic = {
    ...emptyConnection(),
    key: 'anthropic',
    name: 'Anthropic',
    provider: 'ANTHROPIC' as const,
  };

  it('accepts an Anthropic API key', () => {
    expect(connectionProblems({ ...anthropic, apiKey: 'sk-ant-api03-abc' }, [], true)).toEqual([]);
  });

  it('rejects a Claude subscription token as an Anthropic API key (ADR 0048)', () => {
    const problems = connectionProblems({ ...anthropic, apiKey: ' sk-ant-oat01-abc' }, [], true);
    expect(problems.length).toBe(1);
    expect(problems[0]).toContain('subscription token');
    expect(isSubscriptionToken({ provider: 'OPENAI_COMPATIBLE', apiKey: 'sk-ant-oat01-abc' })).toBe(
      false,
    );
    expect(isSubscriptionToken({ provider: 'ANTHROPIC', apiKey: null })).toBe(false);
  });
});

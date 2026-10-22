import {
  canEditConnection,
  connectionProblems,
  connectionScopes,
  effectiveConnectionScope,
  emptyConnection,
  isSubscriptionToken,
} from './llm-providers';

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

  it('creates platform connections by default and personal ones on request (ADR 0050 step 2b)', () => {
    expect(emptyConnection().personal).toBe(false);
    expect(emptyConnection(true).personal).toBe(true);
  });

  it('offers the platform scope only to administrators', () => {
    expect(connectionScopes(true)).toEqual(['PLATFORM', 'PERSONAL']);
    expect(connectionScopes(false)).toEqual(['PERSONAL']);
    expect(effectiveConnectionScope('PLATFORM', true)).toBe('PLATFORM');
    expect(effectiveConnectionScope('PLATFORM', false)).toBe('PERSONAL');
    expect(effectiveConnectionScope('PERSONAL', true)).toBe('PERSONAL');
  });

  it('lets the owner edit personal connections and administrators platform ones', () => {
    expect(canEditConnection({ id: 'a', personal: true }, false)).toBe(true);
    expect(canEditConnection({ id: 'a', personal: false }, false)).toBe(false);
    expect(canEditConnection({ id: 'a', personal: false }, true)).toBe(true);
    expect(canEditConnection({ id: 'a' }, true)).toBe(true);
    // environment endpoints are read-only for everyone
    expect(canEditConnection({ id: null }, true)).toBe(false);
    expect(canEditConnection(null, true)).toBe(false);
  });
});

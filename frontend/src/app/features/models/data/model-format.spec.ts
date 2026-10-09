import type { ModelProfile } from '../../../core/api/types';
import { contextLabel, filterProfiles, perMTok, providerLabel } from './model-format';

const P = (over: Partial<ModelProfile> & { provider?: string; modelId?: string }): ModelProfile =>
  ({
    id: over.id ?? over.displayName ?? 'x',
    displayName: over.displayName ?? 'X',
    ref: { provider: over.provider ?? 'ANTHROPIC', modelId: over.modelId ?? 'm' },
    pricing: { inputPerMTok: 1, outputPerMTok: 5, currency: 'USD' },
    enabled: over.enabled,
    notes: over.notes,
  }) as ModelProfile;

describe('model format', () => {
  it('labels providers with brand casing', () => {
    expect(providerLabel('OPENAI')).toBe('OpenAI');
    expect(providerLabel('OPENAI_COMPATIBLE')).toBe('OpenAI-compatible');
    expect(providerLabel('NEW_ONE')).toBe('New one');
    expect(providerLabel(null)).toBe('-');
  });

  it('formats prices and context windows', () => {
    expect(perMTok(10)).toBe('$10.00');
    expect(perMTok(0.25)).toBe('$0.25');
    expect(perMTok(null)).toBe('-');
    expect(contextLabel(200_000)).toBe('200k');
    expect(contextLabel(1_000_000)).toBe('1M');
    expect(contextLabel(null)).toBe('-');
  });

  it('filters by search, provider and state', () => {
    const list = [
      P({ displayName: 'Claude Opus', modelId: 'claude-opus-5' }),
      P({ displayName: 'GPT-5', provider: 'OPENAI', modelId: 'gpt-5', enabled: false }),
      P({ displayName: 'Gemini', provider: 'GOOGLE', notes: 'placeholder pricing' }),
    ];
    const names = (l: ModelProfile[]) => l.map((p) => p.displayName);
    expect(names(filterProfiles(list, { query: 'opus' }))).toEqual(['Claude Opus']);
    expect(names(filterProfiles(list, { query: 'openai' }))).toEqual(['GPT-5']);
    expect(names(filterProfiles(list, { query: 'placeholder' }))).toEqual(['Gemini']);
    expect(names(filterProfiles(list, { provider: 'GOOGLE' }))).toEqual(['Gemini']);
    expect(names(filterProfiles(list, { state: 'disabled' }))).toEqual(['GPT-5']);
    expect(filterProfiles(list, { state: 'enabled' }).length).toBe(2);
    expect(filterProfiles(list, {}).length).toBe(3);
  });
});

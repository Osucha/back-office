import type { ModelDefaults, ModelProfile } from '../../../core/api/types';
import {
  allowedScopes,
  canEditProfile,
  defaultModelRows,
  defaultProfile,
  effectiveScope,
  filterByScope,
} from './model-scope';

const P = (id: string, personal = false): ModelProfile =>
  ({
    id,
    displayName: id,
    personal,
    enabled: true,
    ref: { provider: 'ANTHROPIC', modelId: 'm' },
    pricing: { inputPerMTok: 1, outputPerMTok: 5, currency: 'USD' },
  }) as ModelProfile;

describe('model scope', () => {
  it('lets the owner edit personal profiles and managers every profile', () => {
    expect(canEditProfile(P('a'), true)).toBe(true);
    expect(canEditProfile(P('a'), false)).toBe(false);
    expect(canEditProfile(P('mine', true), false)).toBe(true);
    expect(canEditProfile(null, false)).toBe(true);
    expect(canEditProfile({ id: undefined as unknown as string, personal: false }, false)).toBe(
      true,
    );
  });

  it('offers the platform scope only to managers and never for a login profile', () => {
    expect(allowedScopes(true, 'API')).toEqual(['PLATFORM', 'PERSONAL']);
    expect(allowedScopes(true, undefined)).toEqual(['PLATFORM', 'PERSONAL']);
    expect(allowedScopes(true, 'CLAUDE_CODE_LOGIN')).toEqual(['PERSONAL']);
    expect(allowedScopes(false, 'API')).toEqual(['PERSONAL']);
    expect(effectiveScope('PLATFORM', true, 'API')).toBe('PLATFORM');
    expect(effectiveScope('PLATFORM', true, 'CLAUDE_CODE_LOGIN')).toBe('PERSONAL');
    expect(effectiveScope('PLATFORM', false, 'API')).toBe('PERSONAL');
    expect(effectiveScope('PERSONAL', true, 'API')).toBe('PERSONAL');
  });

  it('keeps a profile on a personal connection personal, managers included (ADR 0050 step 2b)', () => {
    expect(allowedScopes(true, 'API', true)).toEqual(['PERSONAL']);
    expect(allowedScopes(true, 'API', false)).toEqual(['PLATFORM', 'PERSONAL']);
    expect(effectiveScope('PLATFORM', true, 'API', true)).toBe('PERSONAL');
  });

  it('filters profiles by scope', () => {
    const list = [P('a'), P('b', true), P('c')];
    expect(filterByScope(list, 'platform').map((p) => p.id)).toEqual(['a', 'c']);
    expect(filterByScope(list, 'mine').map((p) => p.id)).toEqual(['b']);
    expect(filterByScope(list, null)).toHaveLength(3);
  });

  it('reads the default of a step kind per scope', () => {
    const defaults: ModelDefaults = {
      platform: { LLM_CALL: P('p') },
      mine: { CODING_AGENT: P('m', true) },
    };
    expect(defaultProfile(defaults, 'PLATFORM', 'LLM_CALL')?.id).toBe('p');
    expect(defaultProfile(defaults, 'PLATFORM', 'CODING_AGENT')).toBeNull();
    expect(defaultProfile(defaults, 'PERSONAL', 'CODING_AGENT')?.id).toBe('m');
    expect(defaultProfile(null, 'PERSONAL', 'LLM_CALL')).toBeNull();
    expect(defaultModelRows().map((r) => [r.stepKind, r.harness])).toEqual([
      ['LLM_CALL', null],
      ['CODING_AGENT', 'CLAUDE_CODE'],
    ]);
  });
});

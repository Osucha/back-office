import type { ProviderCatalogSection } from '../../../core/api/types';
import {
  catalogKey,
  catalogRows,
  filterCatalog,
  isLoginProfile,
  loginKey,
  loginModels,
  profileFromCatalog,
  profileFromLogin,
  profileKey,
} from './model-catalog';

describe('model catalog', () => {
  const sections: ProviderCatalogSection[] = [
    {
      connection: 'openrouter',
      name: 'OpenRouter',
      provider: 'OPENAI_COMPATIBLE',
      source: 'DATABASE',
      ok: true,
      models: [
        {
          id: 'google/gemma-4-31b-it:free',
          name: 'Gemma 4 (free)',
          contextWindow: 262144,
          inputPerMTok: 0,
          outputPerMTok: 0,
          free: true,
        },
        {
          id: 'vendor/paid',
          name: 'Paid',
          contextWindow: 32000,
          inputPerMTok: 2.5,
          outputPerMTok: 10,
          free: false,
        },
      ],
    },
    {
      connection: null,
      name: 'anthropic (environment)',
      provider: 'ANTHROPIC',
      source: 'ENVIRONMENT',
      ok: true,
      models: [{ id: 'claude-x', free: false }],
    },
  ];

  it('flattens sections and filters by query, connection and free', () => {
    const rows = catalogRows(sections);
    expect(rows.map((r) => r.key)).toEqual([
      'openrouter|google/gemma-4-31b-it:free',
      'openrouter|vendor/paid',
      'env:ANTHROPIC|claude-x',
    ]);
    expect(filterCatalog(rows, { freeOnly: true }).map((r) => r.model.id)).toEqual([
      'google/gemma-4-31b-it:free',
    ]);
    expect(filterCatalog(rows, { connection: 'env:ANTHROPIC' }).length).toBe(1);
    expect(filterCatalog(rows, { query: 'PAID' }).map((r) => r.model.id)).toEqual(['vendor/paid']);
  });

  it('prefills a profile with connection, prices and context', () => {
    const [free, paid, env] = catalogRows(sections);
    const p = profileFromCatalog(free);
    expect(p.id).toBeUndefined();
    expect(p.ref).toEqual({
      provider: 'OPENAI_COMPATIBLE',
      modelId: 'google/gemma-4-31b-it:free',
      connection: 'openrouter',
    });
    expect(p.capabilities).toEqual(['CHEAP', 'LONG_CONTEXT']);
    expect(p.contextWindow).toBe(262144);
    expect(profileFromCatalog(paid).pricing).toEqual({
      inputPerMTok: 2.5,
      outputPerMTok: 10,
      currency: 'USD',
    });
    expect(profileFromCatalog(paid).notes).toBe('');
    expect(profileFromCatalog(env).notes).not.toBe('');
    expect(catalogKey('ANTHROPIC', null, 'claude-x')).toBe(env.key);
  });

  it('offers Claude Code login models as unbilled coding profiles (ADR 0048)', () => {
    const models = loginModels();
    expect(models.map((m) => m.alias)).toEqual(['opus', 'sonnet', 'haiku']);
    const p = profileFromLogin(models[0]);
    expect(p.id).toBeUndefined();
    expect(p.runtime).toBe('CLAUDE_CODE_LOGIN');
    expect(p.ref).toEqual({ provider: 'ANTHROPIC', modelId: 'opus', connection: null });
    expect(p.pricing).toEqual({ inputPerMTok: 0, outputPerMTok: 0, currency: 'USD' });
    expect(p.capabilities).toEqual(['CODING']);
    expect(isLoginProfile(p)).toBe(true);
    expect(isLoginProfile({ runtime: 'API' })).toBe(false);
    expect(isLoginProfile({})).toBe(false);
    expect(profileKey(p)).toBe(loginKey('opus'));
    expect(profileKey({ ref: { provider: 'ANTHROPIC', modelId: 'opus', connection: null } })).toBe(
      'env:ANTHROPIC|opus',
    );
  });
});

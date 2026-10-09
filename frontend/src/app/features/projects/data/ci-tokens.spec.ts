import type { CiToken } from '../../../core/api/types';
import {
  maskedToken,
  newEntrypoint,
  openTokenModal,
  sortTokens,
  tokenCreated,
  tokenNameError,
  validateEntrypoints,
} from './ci-tokens';

const tokens: CiToken[] = [
  { id: '1', name: 'gitlab-ci', prefix: 'bo_ci_abc', createdAt: '2026-01-01T00:00:00Z' },
  {
    id: '2',
    name: 'old',
    prefix: 'bo_ci_old',
    createdAt: '2026-02-01T00:00:00Z',
    revokedAt: '2026-03-01T00:00:00Z',
  },
  { id: '3', name: 'newer', prefix: 'bo_ci_new', createdAt: '2026-04-01T00:00:00Z' },
];

describe('token modal', () => {
  it('opens with a suggested name derived from the active token count', () => {
    expect(openTokenModal([])).toEqual({ step: 'naming', name: 'gitlab-ci', error: null });
    expect(openTokenModal(tokens)).toEqual({ step: 'naming', name: 'ci-token-3', error: null });
  });

  it('validates names against active tokens only', () => {
    expect(tokenNameError('', tokens)).toBe('Name is required');
    expect(tokenNameError('gitlab-ci', tokens)).toContain('already exists');
    expect(tokenNameError('old', tokens)).toBeNull();
    expect(tokenNameError('x'.repeat(61), tokens)).toBe('At most 60 characters');
    expect(tokenNameError('fresh', tokens)).toBeNull();
  });

  it('moves to the created step with the plaintext shown once and not yet copied', () => {
    const state = tokenCreated({ id: '9', name: 'n', token: 'bo_ci_secret' });
    expect(state.step).toBe('created');
    if (state.step === 'created') {
      expect(state.token.token).toBe('bo_ci_secret');
      expect(state.copied).toBe(false);
    }
  });

  it('sorts active tokens first, newest first, and masks prefixes', () => {
    expect(sortTokens(tokens).map((t) => t.id)).toEqual(['3', '1', '2']);
    expect(maskedToken('bo_ci_abc')).toBe('bo_ci_abc••••••••');
    expect(maskedToken(undefined)).toMatch(/^bo_ci_/);
  });
});

describe('entrypoints', () => {
  it('validates keys, uniqueness and workflow selection', () => {
    const errors = validateEntrypoints([
      { key: 'code-review', workflowId: 'w1', mode: 'BLOCKING', uploadDiff: true },
      { key: 'code-review', workflowId: '', mode: 'ADVISORY', uploadDiff: false },
      { key: 'Bad Key', workflowId: 'w2', mode: 'BLOCKING', uploadDiff: true },
    ]);
    expect(errors).toEqual([
      'code-review: duplicate key',
      'code-review: pick a workflow',
      'Bad Key: key must be a slug (e.g. code-review)',
    ]);
    expect(
      validateEntrypoints([
        { key: 'code-review', workflowId: 'w1', mode: 'BLOCKING', uploadDiff: true },
      ]),
    ).toEqual([]);
  });

  it('accepts any or a known repository and refuses an unknown one (ADR 0034)', () => {
    const e = { key: 'docs-review', workflowId: 'w1', mode: 'BLOCKING' as const, uploadDiff: true };
    expect(validateEntrypoints([{ ...e, repository: null }], ['main', 'docs'])).toEqual([]);
    expect(validateEntrypoints([{ ...e, repository: 'docs' }], ['main', 'docs'])).toEqual([]);
    expect(validateEntrypoints([{ ...e, repository: 'gone' }], ['main'])).toEqual([
      'docs-review: the project has no repository gone',
    ]);
    // without the project's repositories the key is not checked
    expect(validateEntrypoints([{ ...e, repository: 'gone' }])).toEqual([]);
  });

  it('suggests code-review first, then unique fallbacks', () => {
    expect(newEntrypoint([], 'w').key).toBe('code-review');
    const second = newEntrypoint([
      { key: 'code-review', workflowId: 'w', mode: 'BLOCKING', uploadDiff: true },
    ]);
    expect(second.key).toBe('entrypoint');
    expect(second.mode).toBe('BLOCKING');
    expect(second.uploadDiff).toBe(true);
    expect(
      newEntrypoint([
        { key: 'code-review', workflowId: 'w', mode: 'BLOCKING', uploadDiff: true },
        { key: 'entrypoint', workflowId: 'w', mode: 'BLOCKING', uploadDiff: true },
      ]).key,
    ).toBe('entrypoint-2');
  });
});

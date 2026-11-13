import { applyShortName, shortModelNames } from './model-names';

describe('shortModelNames', () => {
  it('removes a parenthesized suffix every name ends with and reports it', () => {
    const r = shortModelNames([
      'Claude Opus (my login)',
      'Claude Haiku (my login)',
      'GPT-5 (my login)',
    ]);
    expect(r.names).toEqual(['Claude Opus', 'Claude Haiku', 'GPT-5']);
    expect(r.suffix).toBe('my login');
    expect(r.prefix).toBeNull();
  });

  it('removes a leading word every name starts with', () => {
    const r = shortModelNames([
      'Claude Opus (my login)',
      'Claude Sonnet (my login)',
      'Claude Haiku (my login)',
    ]);
    expect(r.names).toEqual(['Opus', 'Sonnet', 'Haiku']);
    expect(r.suffix).toBe('my login');
    expect(r.prefix).toBe('Claude');
  });

  it('keeps names that differ in suffix or vendor', () => {
    const r = shortModelNames(['Claude Opus (login)', 'Claude Opus (API)', 'GPT-5']);
    expect(r.names).toEqual(['Claude Opus (login)', 'Claude Opus (API)', 'GPT-5']);
    expect(r.suffix).toBeNull();
    expect(r.prefix).toBeNull();
  });

  it('keeps the input order and repeated names', () => {
    expect(shortModelNames(['Claude Haiku', 'Claude Opus', 'Claude Haiku']).names).toEqual([
      'Haiku',
      'Opus',
      'Haiku',
    ]);
  });

  it('does not strip the vendor word of a single model or a one-word name', () => {
    expect(shortModelNames(['Claude Opus']).names).toEqual(['Claude Opus']);
    expect(shortModelNames(['Claude', 'Claude Opus']).names).toEqual(['Claude', 'Claude Opus']);
  });

  it('strips a suffix of a single model', () => {
    const r = shortModelNames(['Claude Opus (my login)']);
    expect(r.names).toEqual(['Claude Opus']);
    expect(r.suffix).toBe('my login');
  });

  it('does not make different names equal', () => {
    expect(shortModelNames(['Opus (x)', 'Opus  (x)']).names).toEqual(['Opus (x)', 'Opus  (x)']);
  });

  it('handles empty input', () => {
    expect(shortModelNames([])).toEqual({ names: [], suffix: null, prefix: null });
  });
});

describe('applyShortName', () => {
  it('shortens another name with the removed suffix and word when it has them', () => {
    const short = { suffix: 'my login', prefix: 'Claude' };
    expect(applyShortName('Claude Opus (my login)', short)).toBe('Opus');
    expect(applyShortName('Claude Opus (API)', short)).toBe('Opus (API)');
    expect(applyShortName('GPT-5', short)).toBe('GPT-5');
    expect(applyShortName('Claude', short)).toBe('Claude');
    expect(applyShortName('Claude Opus', { suffix: null, prefix: null })).toBe('Claude Opus');
  });
});

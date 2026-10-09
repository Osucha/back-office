import { fuzzyFilter, fuzzyScore, highlightParts, scoreToken } from './fuzzy';

describe('fuzzy scorer', () => {
  it('matches subsequences case-insensitively and rejects non-matches', () => {
    expect(scoreToken('rns', 'Runs')).not.toBeNull();
    expect(scoreToken('xyz', 'Runs')).toBeNull();
    expect(scoreToken('runsx', 'Runs')).toBeNull();
  });

  it('prefers prefixes, word starts and contiguous matches', () => {
    const prefix = scoreToken('pro', 'Projects')!.score;
    const inner = scoreToken('pro', 'Improve')!.score;
    expect(prefix).toBeGreaterThan(inner);

    const wordStarts = scoreToken('sp', 'Start project')!.score;
    const scattered = scoreToken('sp', 'Settings panel')?.score ?? 0;
    expect(wordStarts).toBeGreaterThanOrEqual(scattered);

    const contiguous = scoreToken('run', 'Runs')!.score;
    const gappy = scoreToken('run', 'Retry unknown node')!.score;
    expect(contiguous).toBeGreaterThan(gappy);
    expect(scoreToken('start', 'Administration: Grants')).toBeNull();
  });

  it('requires every token and weights the title over keywords', () => {
    expect(fuzzyScore('start run', ['Start run…', 'action workflow'])).not.toBeNull();
    expect(fuzzyScore('start zebra', ['Start run…'])).toBeNull();
    const title = fuzzyScore('shop', ['SHOP', 'project'])!.score;
    const keyword = fuzzyScore('shop', ['Checkout', 'SHOP project'])!.score;
    expect(title).toBeGreaterThan(keyword);
  });

  it('finds runs by id prefix and issue key', () => {
    const runs = [
      { title: 'SHOP-12 · Issue to MR', id: '1f32ab00-0000' },
      { title: 'SHOP-120 · Code review', id: '9a00ff00-0000' },
      { title: 'CORE-7 · Issue to MR', id: '77aa0000-0000' },
    ];
    const byKey = fuzzyFilter(runs, 'shop-12', (r) => [r.title, r.id]);
    expect(byKey[0].item.title).toBe('SHOP-12 · Issue to MR');
    const byId = fuzzyFilter(runs, '9a00', (r) => [r.title, r.id]);
    expect(byId.map((x) => x.item.id)).toEqual(['9a00ff00-0000']);
  });

  it('keeps the order for an empty query and highlights matches', () => {
    const items = ['b', 'a'];
    expect(fuzzyFilter(items, '  ', (x) => [x]).map((x) => x.item)).toEqual(['b', 'a']);
    const m = fuzzyScore('prj', ['Projects'])!;
    expect(
      highlightParts('Projects', m.indices)
        .filter((p) => p.hit)
        .map((p) => p.text)
        .join(''),
    ).toBe('Prj');
  });
});

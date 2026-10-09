import { hasActiveFilters, parseFilters, serializeFilters } from './filter-state';

describe('filter state in the URL', () => {
  const spec = { q: 'string', holder: 'list', projectId: 'string' } as const;

  it('parses query parameters', () => {
    expect(parseFilters(spec, { q: ' SHOP-12 ', holder: 'agent,you,', other: 'x' })).toEqual({
      q: 'SHOP-12',
      holder: ['agent', 'you'],
      projectId: null,
    });
    expect(parseFilters(spec, {})).toEqual({ q: null, holder: [], projectId: null });
  });

  it('serialises only the given keys and removes empty ones', () => {
    expect(serializeFilters(spec, { holder: ['done'], q: '' })).toEqual({
      holder: 'done',
      q: null,
    });
    expect(serializeFilters(spec, { holder: [] })).toEqual({ holder: null });
  });

  it('knows when a filter is active', () => {
    expect(hasActiveFilters(spec, { q: null, holder: [], projectId: null })).toBe(false);
    expect(hasActiveFilters(spec, { q: null, holder: ['ci'], projectId: null })).toBe(true);
  });
});

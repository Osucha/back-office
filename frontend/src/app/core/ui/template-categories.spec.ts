import { categoryLabel, categoryOrder, groupByCategory } from './template-categories';

describe('template categories (ADR 0055)', () => {
  it('labels categories and treats none as General', () => {
    expect(categoryLabel('ANALYSIS')).toBe('Analysis');
    expect(categoryLabel('JIRA')).toBe('Jira');
    expect(categoryLabel(null)).toBe('General');
    expect(categoryLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('orders categories as listed, General last', () => {
    expect(categoryOrder('ANALYSIS')).toBeLessThan(categoryOrder('REVIEW'));
    expect(categoryOrder(undefined)).toBe(categoryOrder('GENERAL'));
    expect(categoryOrder('SOMETHING_NEW')).toBeLessThan(categoryOrder('GENERAL'));
    expect(categoryOrder('SOMETHING_NEW')).toBeGreaterThan(categoryOrder('OPERATIONS'));
  });

  it('groups items in display order and puts those without a category under General', () => {
    const items = [
      { k: 'a', c: 'REVIEW' },
      { k: 'b', c: null },
      { k: 'c', c: 'ANALYSIS' },
      { k: 'd', c: 'REVIEW' },
    ];
    const groups = groupByCategory(items, (i) => i.c);
    expect(groups.map((g) => [g.category, g.label, g.items.map((i) => i.k)])).toEqual([
      ['ANALYSIS', 'Analysis', ['c']],
      ['REVIEW', 'Review', ['a', 'd']],
      ['GENERAL', 'General', ['b']],
    ]);
  });
});

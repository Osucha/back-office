import { deprecatedKeys, isDeprecated, withoutDeprecated } from './template-tags';

describe('template tags', () => {
  const list = [
    { key: 'implement-claude-code', tags: ['deprecated', 'coding'] },
    { key: 'analyse-issue', tags: ['Deprecated'] },
    { key: 'code-review', tags: ['review'] },
    { key: 'plain' },
    { key: 'nulls', tags: null },
  ];

  it('recognises the deprecated tag case-insensitively', () => {
    expect(isDeprecated(list[0])).toBe(true);
    expect(isDeprecated(list[1])).toBe(true);
    expect(isDeprecated(list[2])).toBe(false);
    expect(isDeprecated(list[3])).toBe(false);
    expect(isDeprecated(list[4])).toBe(false);
    expect(isDeprecated(null)).toBe(false);
  });

  it('hides deprecated items unless asked to show them', () => {
    expect(withoutDeprecated(list).map((t) => t.key)).toEqual(['code-review', 'plain', 'nulls']);
    expect(withoutDeprecated(list, true).length).toBe(5);
  });

  it('collects the keys of deprecated items', () => {
    expect([...deprecatedKeys(list)]).toEqual(['implement-claude-code', 'analyse-issue']);
  });
});

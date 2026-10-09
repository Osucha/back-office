import { compareKeys, compareText } from './compare';
import { activeLocale, setActiveLocale } from './locale';

describe('comparators', () => {
  it('compareKeys keeps the default code-unit order', () => {
    const keys = ['runs.read', 'Projects.write', 'admin', 'runs.cancel', 'b'];
    expect([...keys].sort(compareKeys)).toEqual([
      'Projects.write',
      'admin',
      'b',
      'runs.cancel',
      'runs.read',
    ]);
    expect(compareKeys('a', 'a')).toBe(0);
  });

  it('compareText collates in the active language', () => {
    const before = activeLocale();
    try {
      setActiveLocale('pl');
      expect(['źle', 'zero', 'ćma', 'cebula'].sort(compareText)).toEqual([
        'cebula',
        'ćma',
        'zero',
        'źle',
      ]);
    } finally {
      setActiveLocale(before);
    }
  });
});

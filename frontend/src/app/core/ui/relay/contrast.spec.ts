import { checkContrast, contrastRatio, mix, parseHex, parseThemes } from './contrast';

describe('contrast math (npm run check:contrast)', () => {
  it('computes WCAG ratios', () => {
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 5);
    expect(contrastRatio('#FFFFFF', '#FFFFFF')).toBeCloseTo(1, 5);
    // Known pair: #767676 on white is the classic 4.54:1.
    expect(contrastRatio('#767676', '#fff')).toBeCloseTo(4.54, 1);
  });

  it('parses and mixes colours', () => {
    expect(parseHex('#abc')).toEqual({ r: 170, g: 187, b: 204 });
    expect(() => parseHex('red')).toThrow();
    expect(mix('#000000', '#FFFFFF', 0.5)).toBe('#808080');
  });

  it('reads the light and dark blocks and flags failing pairs', () => {
    const css = `
      :root { /* @theme light */ --bo-ink: #111111; --bo-bg: #ffffff; /* @end */ --bo-x: 1px; }
      [data-theme='dark'] { /* @theme dark */ --bo-ink: #333333; --bo-bg: #222222; /* @end */ }`;
    const themes = parseThemes(css);
    expect(themes.light).toEqual({ ink: '#111111', bg: '#ffffff' });
    const results = checkContrast(themes, [{ fg: 'ink', bg: 'bg', min: 4.5, why: 'text' }]);
    expect(results.map((r) => [r.theme, r.ok])).toEqual([
      ['light', true],
      ['dark', false],
    ]);
  });

  it('reports missing tokens as failures', () => {
    const [r] = checkContrast({ light: {} }, [{ fg: 'ink', bg: 'bg', min: 4.5, why: 'text' }]);
    expect(r.ok).toBe(false);
  });
});

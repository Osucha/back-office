// i18n-ignore-file: contrast pairs for npm run check:contrast, no UI text.
/**
 * WCAG 2.2 contrast math for the Relay tokens. Pure and dependency-free: used by the unit tests and
 * by `npm run check:contrast` (scripts/check-contrast.ts, run with Node's type stripping), which
 * parses `src/styles/relay/tokens.css`. Keep this file free of imports and of TypeScript-only
 * runtime syntax (enums, namespaces, parameter properties).
 */

export interface Rgb {
  r: number;
  g: number;
  b: number;
}

/** `#RGB` / `#RRGGBB` to channels 0..255; throws on anything else. */
export function parseHex(hex: string): Rgb {
  const h = hex.trim().replace(/^#/, '');
  const full =
    h.length === 3
      ? h
          .split('')
          .map((c) => c + c)
          .join('')
      : h;
  if (!/^[0-9a-fA-F]{6}$/.test(full)) throw new Error(`Not a hex colour: ${hex}`);
  return {
    r: parseInt(full.slice(0, 2), 16),
    g: parseInt(full.slice(2, 4), 16),
    b: parseInt(full.slice(4, 6), 16),
  };
}

export function toHex({ r, g, b }: Rgb): string {
  const c = (v: number) =>
    Math.round(Math.min(255, Math.max(0, v)))
      .toString(16)
      .padStart(2, '0')
      .toUpperCase();
  return `#${c(r)}${c(g)}${c(b)}`;
}

/** Relative luminance (WCAG 2.x). */
export function luminance(hex: string): number {
  const { r, g, b } = parseHex(hex);
  const lin = (v: number) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

/** Contrast ratio between two opaque colours, 1..21. */
export function contrastRatio(a: string, b: string): number {
  const la = luminance(a);
  const lb = luminance(b);
  const [hi, lo] = la > lb ? [la, lb] : [lb, la];
  return (hi + 0.05) / (lo + 0.05);
}

/** `weight` of `color` over `base` (a tint on light, a wash on dark); 0..1. */
export function mix(color: string, base: string, weight: number): string {
  const c = parseHex(color);
  const s = parseHex(base);
  return toHex({
    r: s.r + (c.r - s.r) * weight,
    g: s.g + (c.g - s.g) * weight,
    b: s.b + (c.b - s.b) * weight,
  });
}

/** `--bo-name: #hex;` declarations of one CSS block, by token name (without the leading dashes). */
export function parseTokenBlock(css: string): Record<string, string> {
  const out: Record<string, string> = {};
  const re = /--bo-([a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,6})\s*;/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(css))) out[m[1]] = m[2];
  return out;
}

/**
 * The light and dark token sets of `tokens.css`: the light block is marked `/* @theme light *\/`,
 * the dark one `/* @theme dark *\/`; each ends at the next `@end` marker.
 */
export function parseThemes(css: string): {
  light: Record<string, string>;
  dark: Record<string, string>;
} {
  const block = (name: string) => {
    const start = css.indexOf(`@theme ${name}`);
    if (start < 0) throw new Error(`tokens.css: missing "@theme ${name}" marker`);
    const end = css.indexOf('@end', start);
    return parseTokenBlock(css.slice(start, end < 0 ? undefined : end));
  };
  return { light: block('light'), dark: block('dark') };
}

export interface ContrastPair {
  fg: string;
  bg: string;
  /** 4.5 for body text, 3 for large text and non-text UI (glyphs, focus rings). */
  min: number;
  why: string;
}

/** Every pair the UI relies on. Text tokens on each background, chip text on its tint, glyphs. */
export const CONTRAST_PAIRS: ContrastPair[] = [
  ...['ink', 'ink-2', 'ink-3', 'accent'].flatMap((fg) =>
    ['bg', 'surface', 'surface-2'].map((bg) => ({ fg, bg, min: 4.5, why: 'text' })),
  ),
  { fg: 'on-ink', bg: 'ink', min: 4.5, why: 'primary button label' },
  { fg: 'on-accent', bg: 'accent', min: 4.5, why: 'accent fill label' },
  ...['agent', 'you', 'ci', 'done', 'blocked', 'idle'].flatMap((h) => [
    { fg: `${h}-ink`, bg: `${h}-soft`, min: 4.5, why: 'baton chip text on its tint' },
    { fg: `${h}-ink`, bg: 'surface', min: 4.5, why: 'baton word on a surface' },
    { fg: h, bg: 'surface', min: 3, why: 'baton glyph / track segment (non-text)' },
  ]),
  { fg: 'accent', bg: 'surface', min: 3, why: 'focus ring' },
  { fg: 'line-strong', bg: 'surface', min: 1.3, why: 'input outline (decorative minimum)' },
];

export interface ContrastResult extends ContrastPair {
  theme: string;
  ratio: number;
  ok: boolean;
}

export function checkContrast(
  themes: Record<string, Record<string, string>>,
  pairs: ContrastPair[] = CONTRAST_PAIRS,
): ContrastResult[] {
  const out: ContrastResult[] = [];
  for (const [theme, tokens] of Object.entries(themes)) {
    for (const p of pairs) {
      const fg = tokens[p.fg];
      const bg = tokens[p.bg];
      if (!fg || !bg) {
        out.push({ ...p, theme, ratio: 0, ok: false, why: `${p.why} (token missing)` });
        continue;
      }
      const ratio = contrastRatio(fg, bg);
      out.push({ ...p, theme, ratio, ok: ratio >= p.min });
    }
  }
  return out;
}

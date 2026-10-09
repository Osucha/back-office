// npm run check:contrast - WCAG contrast of the Relay token pairs in both themes.
// Parses src/styles/relay/tokens.css (the @theme light / @theme dark blocks), checks every pair of
// CONTRAST_PAIRS (text 4.5:1, glyphs and focus 3:1) and that the prefers-color-scheme block repeats
// the dark values. Runs with Node's built-in type stripping (Node 22.18+), no build step.
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkContrast, parseThemes, parseTokenBlock } from '../src/app/core/ui/relay/contrast.ts';

const here = dirname(fileURLToPath(import.meta.url));
const file = resolve(here, '../src/styles/relay/tokens.css');
const css = readFileSync(file, 'utf8');
const themes = parseThemes(css);

const results = checkContrast(themes);
const failed = results.filter((r) => !r.ok);

const width = 28;
for (const r of results) {
  const pair = `${r.fg} on ${r.bg}`.padEnd(width);
  const mark = r.ok ? 'ok  ' : 'FAIL';
  console.log(
    `${mark} ${r.theme.padEnd(5)} ${pair} ${r.ratio.toFixed(2).padStart(5)} (min ${r.min})  ${r.why}`,
  );
}

// The OS-preference block must match the explicit dark theme.
const media = css.slice(css.indexOf('@media (prefers-color-scheme: dark)'));
const mediaTokens = parseTokenBlock(media.slice(0, media.indexOf('}\n}') + 3));
const drift = Object.entries(themes.dark).filter(
  ([k, v]) => (mediaTokens[k] ?? '').toLowerCase() !== v.toLowerCase(),
);
for (const [k, v] of drift) {
  console.log(
    `FAIL dark  --bo-${k}: ${v} differs in the prefers-color-scheme block (${mediaTokens[k] ?? 'missing'})`,
  );
}

console.log(
  `\n${results.length - failed.length}/${results.length} pairs pass${drift.length ? `, ${drift.length} dark token(s) out of sync` : ''}.`,
);
process.exit(failed.length || drift.length ? 1 : 0);

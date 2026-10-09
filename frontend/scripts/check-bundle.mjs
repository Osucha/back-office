// npm run check:bundle (last step of npm run build) - enforces the Relay budget of at most 300 kB of
// initial JavaScript + CSS, gzip-compressed (Angular's budgets measure raw bytes, its "estimated
// transfer size" is not gzip). Initial = the scripts and stylesheets of index.html plus every chunk
// they import statically (dynamic `import()` chunks are lazy and not counted). Exception: the
// dynamic imports of the entry script itself are counted too, because main.ts loads the app with
// `import('./bootstrap')` once the translations are in (ADR 0026); they are part of every start.
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

const LIMIT = 300 * 1024;
const dir = 'dist/back-office-web/browser';
const html = readFileSync(join(dir, 'index.html'), 'utf8');

const queue = [];
const entries = [];
for (const m of html.matchAll(/<(?:script|link)\b[^>]*?(?:src|href)="([^"]+\.(?:js|css))"/g)) {
  queue.push(m[1]);
  if (m[0].startsWith('<script')) entries.push(m[1].replace(/^\.?\//, ''));
}
const seen = new Set();
while (queue.length) {
  const ref = queue.shift().replace(/^\.?\//, '');
  if (seen.has(ref)) continue;
  const file = join(dir, ref);
  if (!existsSync(file)) continue;
  seen.add(ref);
  if (!ref.endsWith('.js')) continue;
  const code = readFileSync(file, 'utf8');
  // Static imports only: `import ... from "./x.js"`, `import "./x.js"`, `export ... from "./x.js"`.
  for (const m of code.matchAll(
    /(?:^|[;}\s])(?:import|export)\s*(?:[^'"();]*?from\s*)?["']\.\/([^"']+\.js)["']/g,
  ))
    queue.push(m[1]);
  // The entry's own dynamic imports: the app (main.ts -> bootstrap) and the locale data.
  if (entries.includes(ref))
    for (const m of code.matchAll(/\bimport\(\s*["']\.\/([^"']+\.js)["']\s*\)/g)) queue.push(m[1]);
}

let total = 0;
const rows = [];
for (const ref of seen) {
  const size = gzipSync(readFileSync(join(dir, ref)), { level: 9 }).length;
  total += size;
  rows.push([ref, size]);
}
rows.sort((a, b) => b[1] - a[1]);
for (const [ref, size] of rows.slice(0, 12))
  console.log(`${(size / 1024).toFixed(1).padStart(7)} kB  ${ref}`);
if (rows.length > 12) console.log(`          ... ${rows.length - 12} more initial files`);
console.log(
  `${(total / 1024).toFixed(1).padStart(7)} kB  initial total (gzip -9), budget ${LIMIT / 1024} kB`,
);
if (total > LIMIT) {
  console.error('Initial bundle over budget (docs/ui/design-language.md, section 8).');
  process.exit(1);
}

// npm run i18n:check (part of npm run build) - the translation gate (ADR 0026).
//
//   npm run i18n:check                          extract (ng extract-i18n --format json) and check
//   npm run i18n:check -- --reuse               reuse the last extraction (fast; after editing only
//                                               src/locale/**)
//   npm run i18n:check -- --area projects       report one area and print its missing ids as JSON
//                                               (English source as the value, ready to translate)
//   npm run i18n:check -- --messages file.json  check a given extraction (tests)
//
// Fails when
//   (a) a message has no custom @@id (or an ICU shares its i18n element with other content),
//   (b) a source id has no Polish translation (areas in src/locale/pending-areas.json excepted),
//   (c) a translation's placeholders differ from the source (plus unbalanced ICU braces and Polish
//       plurals without one / few / many / other),
//   (d) a partial file holds ids that are no longer in the source (stale),
//   (e) an id is in two partial files or in the file of another area,
//   (f) user-visible text is not marked for translation (heuristic; pending areas excepted).
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkTranslations, formatReport, missingForArea } from './i18n-lib.mjs';
import { readPartials } from './i18n-merge.mjs';
import { scanSources } from './i18n-scan.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const opt = (name) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? (args[i + 1] ?? true) : null;
};
const LANG = opt('lang') || 'pl';
const area = opt('area');
const extractDir = join(root, '.angular', 'i18n-extract');
const started = Date.now();

function extract() {
  mkdirSync(extractDir, { recursive: true });
  const ng = join(root, 'node_modules', '@angular', 'cli', 'bin', 'ng.js');
  const r = spawnSync(
    process.execPath,
    [ng, 'extract-i18n', '--format', 'json', '--output-path', extractDir, '--out-file', 'messages.json', '--progress=false'],
    { cwd: root, encoding: 'utf8', env: { ...process.env, NG_CLI_ANALYTICS: 'false' } },
  );
  const output = `${r.stdout ?? ''}${r.stderr ?? ''}`;
  if (r.status !== 0) {
    console.error(output);
    console.error('i18n:check: ng extract-i18n failed');
    process.exit(1);
  }
  // Angular warns (does not fail) when one custom id is used for two different texts.
  const duplicates = [...output.matchAll(/Duplicate messages with id "([^"]+)"/g)].map((m) => m[1]);
  return duplicates;
}

const areas = [
  'shell',
  'core',
  ...readdirSync(join(root, 'src', 'app', 'features')).filter((n) =>
    statSync(join(root, 'src', 'app', 'features', n)).isDirectory(),
  ),
];

let duplicates = [];
let messagesFile = opt('messages');
if (!messagesFile) {
  messagesFile = join(extractDir, 'messages.json');
  if (!opt('reuse') || !existsSync(messagesFile)) duplicates = extract();
}
const source = JSON.parse(readFileSync(messagesFile, 'utf8')).translations ?? {};
const localeDir = join(root, 'src', 'locale');
const { partials, errors } = readPartials(join(localeDir, LANG));
const pending = readJson(join(localeDir, 'pending-areas.json'), { areas: [] }).areas ?? [];
const allow = readJson(join(localeDir, 'untranslatable.json'), { words: [], phrases: [] });
const scan = await scanSources(join(root, 'src'), { allow });

const result = checkTranslations({ source, partials, pending, areas, scan, lang: LANG });
result.problems.unshift(...errors);
for (const id of duplicates)
  result.problems.unshift({
    kind: 'duplicate-source-id',
    area: id.split('.')[0],
    id,
    detail: 'one id used for different texts; give each text its own id',
  });

for (const line of formatReport(result, { area })) console.log(line);
if (area) {
  // The work list of the area, also while it is pending: texts still to tag.
  const todo = scan.untagged.filter((u) => u.area === area);
  if (todo.length) {
    console.log(`\nUntagged in ${area} (${todo.length}):`);
    for (const u of todo)
      console.log(`  ${u.file}  ${u.kind}  ${JSON.stringify(u.text).slice(0, 100)}`);
  }
  const missing = missingForArea(source, partials, area);
  console.log(
    `\nMissing in src/locale/${LANG}/${area}.json (${Object.keys(missing).length}), English source as value:`,
  );
  console.log(JSON.stringify(missing, null, 2));
}
const failing = result.problems.filter((p) => !area || p.area === area).length;
console.log(
  `\ni18n:check: ${Object.keys(source).length} messages, ${failing} problem(s), pending areas: ${
    pending.join(', ') || 'none'
  } (${((Date.now() - started) / 1000).toFixed(1)} s)`,
);
if (failing) process.exit(1);

function readJson(file, fallback) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
}

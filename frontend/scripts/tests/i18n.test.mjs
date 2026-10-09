// node --test scripts/tests/ (part of npm run test:ci): the i18n tooling on fixtures.
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  areaOfFile,
  areaOfId,
  checkTranslations,
  formatReport,
  isAutoId,
  mergePartials,
  missingForArea,
  placeholdersOf,
  pluralBlocks,
} from '../i18n-lib.mjs';
import { mergeLanguage } from '../i18n-merge.mjs';
import { scanSources } from '../i18n-scan.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const frontend = resolve(here, '..', '..');

test('ids and files map to areas', () => {
  assert.equal(areaOfId('projects.detail.delete.title'), 'projects');
  assert.equal(areaOfId('shell.topbar.help'), 'shell');
  assert.equal(areaOfId('nodots'), null);
  assert.equal(areaOfId('Projects.x'), null);
  assert.ok(isAutoId('1363631039017064861'));
  assert.ok(!isAutoId('core.http.notFound'));
  assert.equal(areaOfFile('app/features/projects/pages/x.ts'), 'projects');
  assert.equal(areaOfFile('src/app/features/evaluation/suites/pages/x.ts'), 'evaluation');
  assert.equal(areaOfFile('app/core/ui/x.ts'), 'core');
  assert.equal(areaOfFile('app/layout/rail.component.ts'), 'shell');
  assert.equal(areaOfFile('app/app.routes.ts'), 'shell');
  assert.equal(areaOfFile('bootstrap.ts'), 'shell');
});

test('placeholders of templates, $localize and ICU', () => {
  assert.deepEqual(
    placeholdersOf('Hi {$INTERPOLATION} and {$START_TAG_STRONG}x{$CLOSE_TAG_STRONG}'),
    ['$CLOSE_TAG_STRONG', '$INTERPOLATION', '$START_TAG_STRONG'],
  );
  assert.deepEqual(placeholdersOf('{count, plural, one {# run} other {# runs}}'), ['icu:count']);
  assert.deepEqual(
    placeholdersOf('{VAR_PLURAL, plural, one {x} other {{INTERPOLATION} items}}'),
    ['INTERPOLATION', 'icu:VAR_PLURAL'],
  );
  assert.deepEqual(pluralBlocks('{n, plural, =0 {none} one {#} few {#} many {#} other {#}}')[0].keys, [
    '=0',
    'one',
    'few',
    'many',
    'other',
  ]);
});

test('merging partials reports duplicates, wrong files and malformed ids', () => {
  const { translations, problems } = mergePartials({
    projects: { 'projects.a.b': 'A', 'runs.x.y': 'R', bad: 'B' },
    runs: { 'projects.a.b': 'again' },
  });
  assert.equal(translations['projects.a.b'], 'A');
  const kinds = problems.map((p) => p.kind).sort();
  assert.deepEqual(kinds, ['duplicate', 'malformed-id', 'wrong-file', 'wrong-file']);
});

test('the merge script writes the file main.ts fetches', () => {
  const dir = mkdtempSync(join(tmpdir(), 'i18n-merge-'));
  try {
    mkdirSync(join(dir, 'locale', 'pl'), { recursive: true });
    writeFileSync(join(dir, 'locale', 'pl', 'shell.json'), JSON.stringify({ 'shell.a.b': 'Ala' }));
    writeFileSync(join(dir, 'locale', 'pl', 'core.json'), '﻿{"core.x.y": "Kot"}');
    const out = join(dir, 'public', 'i18n');
    const r = mergeLanguage(join(dir, 'locale'), out, 'pl');
    assert.equal(r.count, 2);
    assert.deepEqual(r.problems, []);
    const merged = JSON.parse(readFileSync(join(out, 'messages.pl.json'), 'utf8'));
    assert.equal(merged.locale, 'pl');
    assert.deepEqual(merged.translations, { 'core.x.y': 'Kot', 'shell.a.b': 'Ala' });
    writeFileSync(join(dir, 'locale', 'pl', 'runs.json'), '{ not json');
    assert.equal(mergeLanguage(join(dir, 'locale'), out, 'pl').problems[0].kind, 'invalid-json');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('the gate finds every kind of problem and respects pending areas', () => {
  const source = {
    'shell.nav.home': 'Home',
    'shell.topbar.turn': '{count, plural, one {# decision} other {# decisions}}',
    'shell.rail.tile': 'Project {$INTERPOLATION}, {$INTERPOLATION_1}',
    'shell.missing.one': 'Not translated',
    'projects.list.title': 'Projects',
    '6888392273490092060': '{VAR_PLURAL, plural, one {x} other {y}}',
  };
  const partials = {
    shell: {
      'shell.nav.home': 'Strona główna',
      'shell.topbar.turn': '{count, plural, one {# decyzja} other {# decyzji}}',
      'shell.rail.tile': 'Projekt {$INTERPOLATION}',
      'shell.old.gone': 'Stare',
    },
  };
  const scan = {
    noId: [],
    untagged: [
      { file: 'app/layout/x.ts:3', area: 'shell', kind: 'text', text: 'Hello' },
      { file: 'app/features/projects/x.ts:9', area: 'projects', kind: 'text', text: 'Later' },
    ],
    files: { 'shell.nav.home': [{ file: 'app/core/x.ts:1', area: 'core' }] },
  };
  const result = checkTranslations({
    source,
    partials,
    pending: ['projects'],
    areas: ['shell', 'core', 'projects'],
    scan,
  });
  const kinds = result.problems.map((p) => `${p.kind}:${p.id ?? p.file}`).sort();
  assert.deepEqual(kinds, [
    'id-area-mismatch:shell.nav.home',
    'missing:shell.missing.one',
    'no-custom-id:6888392273490092060',
    'placeholders:shell.rail.tile',
    'plural-forms:shell.topbar.turn',
    'stale:shell.old.gone',
    'untagged:app/layout/x.ts:3',
  ]);
  assert.equal(result.stats.projects.missing, 1);
  assert.equal(result.stats.projects.pending, true);
  assert.equal(result.stats.shell.translated, 3);
  assert.deepEqual(missingForArea(source, partials, 'projects'), { 'projects.list.title': 'Projects' });
  const report = formatReport(result).join('\n');
  assert.match(report, /plural-forms \(1\)/);
  assert.match(report, /shell\s+4\s+3\s+1\s+1\s+checked/);
});

test('a pending area fails as soon as it is removed from the list', () => {
  const source = { 'projects.list.title': 'Projects' };
  const pending = checkTranslations({ source, partials: {}, pending: ['projects'], areas: ['projects'] });
  assert.equal(pending.problems.length, 0);
  const done = checkTranslations({ source, partials: {}, pending: [], areas: ['projects'] });
  assert.equal(done.problems[0].kind, 'missing');
});

test('the scanner finds ids, messages without ids and untagged text', async () => {
  const src = join(here, 'fixtures', 'src');
  const r = await scanSources(src, { allow: { words: ['CI'], phrases: [] } });
  assert.deepEqual(Object.keys(r.files).sort(), [
    'demo.page.count',
    'demo.page.heading',
    'demo.page.icu',
    'demo.page.mixed',
    'demo.page.search',
    'demo.page.title',
  ]);
  assert.equal(r.files['demo.page.title'][0].area, 'demo');
  const noId = r.noId.map((n) => n.text);
  assert.ok(noId.some((t) => t.startsWith('Text without an id')));
  assert.ok(noId.some((t) => t.includes('No custom id')));
  assert.ok(noId.some((t) => t.startsWith('(an ICU must be the only content')));
  const untagged = r.untagged.map((u) => `${u.kind}:${u.text}`).sort();
  assert.deepEqual(untagged, [
    'attribute placeholder:Search runs',
    'binding title:Broken',
    'binding title:Looks good',
    'text:Untagged sentence in a template',
    'ts-notification:Could not save the demo',
    'ts-property:Demo label',
  ]);
});

test('every translated manual chapter carries the anchors of the Polish manifest', () => {
  const dir = resolve(frontend, '..', 'docs', 'manual', 'pl');
  if (!existsSync(join(dir, 'manual.json'))) return;
  const manifest = JSON.parse(readFileSync(join(dir, 'manual.json'), 'utf8'));
  const english = JSON.parse(readFileSync(resolve(dir, '..', 'manual.json'), 'utf8'));
  const anchorsOf = (m) =>
    Object.fromEntries(m.groups.flatMap((g) => g.chapters).map((c) => [c.slug, c.anchors]));
  const en = anchorsOf(english);
  const missing = [];
  for (const [slug, anchors] of Object.entries(anchorsOf(manifest))) {
    // Same anchors as the English chapter (the help map points at them in both languages).
    for (const a of en[slug] ?? []) if (!anchors.includes(a)) missing.push(`manual.json ${slug}#${a}`);
    const file = join(dir, `${slug}.md`);
    if (!existsSync(file)) continue; // not translated yet: the app falls back to English
    const md = readFileSync(file, 'utf8');
    for (const a of anchors) if (!md.includes(`<a id="${a}"></a>`)) missing.push(`${slug}.md#${a}`);
  }
  assert.deepEqual(missing, []);
  assert.ok(readdirSync(dir).length > 0);
});

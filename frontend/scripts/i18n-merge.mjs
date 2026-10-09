// npm run i18n:merge (first steps of start / build / test) - merges the per-area translation files
// src/locale/<lang>/<area>.json into public/i18n/messages.<lang>.json, the file main.ts fetches at
// start-up (git-ignored, generated). Fails on duplicate ids, ids filed under the wrong area and
// invalid JSON, so a broken partial never reaches the browser.
//
//   node scripts/i18n-merge.mjs            all languages under src/locale
//   node scripts/i18n-merge.mjs --watch    and again whenever a partial changes (dev server)
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, watch, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mergePartials } from './i18n-lib.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** Reads src/locale/<lang>/*.json -> { area: entries }; JSON errors are reported per file. */
export function readPartials(dir) {
  const partials = {};
  const errors = [];
  if (!existsSync(dir)) return { partials, errors };
  for (const name of readdirSync(dir).sort()) {
    if (!name.endsWith('.json')) continue;
    const area = name.slice(0, -5);
    try {
      partials[area] = JSON.parse(readFileSync(join(dir, name), 'utf8').replace(/^﻿/, ''));
    } catch (err) {
      errors.push({ kind: 'invalid-json', area, detail: err.message });
    }
  }
  return { partials, errors };
}

/** Languages = sub-folders of src/locale. */
export function languages(localeDir) {
  if (!existsSync(localeDir)) return [];
  return readdirSync(localeDir).filter((n) => statSync(join(localeDir, n)).isDirectory());
}

export function mergeLanguage(localeDir, outDir, lang) {
  const { partials, errors } = readPartials(join(localeDir, lang));
  const { translations, problems } = mergePartials(partials);
  const all = [...errors, ...problems];
  mkdirSync(outDir, { recursive: true });
  const file = join(outDir, `messages.${lang}.json`);
  // Written even with problems so the app keeps working; the exit code reports them.
  writeFileSync(file, JSON.stringify({ locale: lang, translations }, null, 1) + '\n');
  return { file, count: Object.keys(translations).length, problems: all };
}

function run() {
  const localeDir = join(root, 'src', 'locale');
  const outDir = join(root, 'public', 'i18n');
  let failed = false;
  for (const lang of languages(localeDir)) {
    const { count, problems } = mergeLanguage(localeDir, outDir, lang);
    console.log(`i18n:merge: ${lang}: ${count} messages -> public/i18n/messages.${lang}.json`);
    for (const p of problems) {
      failed = true;
      console.error(`  ${p.kind} [${p.area}] ${p.id ?? ''} ${p.detail ?? ''}`);
    }
  }
  return failed;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const failed = run();
  if (process.argv.includes('--watch')) {
    let timer = null;
    watch(join(root, 'src', 'locale'), { recursive: true }, () => {
      clearTimeout(timer);
      timer = setTimeout(run, 150);
    });
    console.log('i18n:merge: watching src/locale');
  } else if (failed) {
    process.exit(1);
  }
}

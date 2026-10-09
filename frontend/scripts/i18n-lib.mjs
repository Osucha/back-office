// Pure helpers of the i18n tooling (scripts/i18n-merge.mjs, scripts/i18n-check.mjs). No file
// system access here, so scripts/tests/i18n.test.mjs can test them on fixtures.
//
// Conventions (docs/guides/frontend-development.md, "Internationalisation"):
//   - every message has a custom id `<area>.<screen>.<name>` (`@@shell.topbar.help`);
//   - the area is the folder the message lives in: `features/<name>/**` -> <name>, `core/**` -> core,
//     `layout/**`, `app.*.ts` and the bootstrap -> shell;
//   - translations live in `src/locale/<lang>/<area>.json` (flat {"id": "target"}), one file per area;
//   - a target keeps the placeholders of the source (`{$INTERPOLATION}`, `{$START_TAG_STRONG}` ...)
//     and ICU stays ICU (Polish plurals need one / few / many / other).

/** `shell.topbar.help`: lower-case area, then one or more segments. */
export const ID_PATTERN = /^([a-z][a-z0-9-]*)\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/;

/** Area of a message id (`projects.detail.title` -> `projects`), or null when the id is malformed. */
export function areaOfId(id) {
  const m = ID_PATTERN.exec(id);
  return m ? m[1] : null;
}

/** Auto-generated ids are decimal digests (no `@@id` was given). */
export function isAutoId(id) {
  return /^\d+$/.test(id);
}

/**
 * Area of a source file, from its path relative to `src/` (forward slashes):
 * `app/features/projects/x.ts` -> projects, `app/core/ui/x.ts` -> core, `app/layout/x.ts`,
 * `app/app.ts`, `bootstrap.ts`, `main.ts` -> shell.
 */
export function areaOfFile(relPath) {
  const p = relPath.replace(/\\/g, '/').replace(/^\.?\/?(src\/)?/, '');
  let m = /^app\/features\/([^/]+)\//.exec(p);
  if (m) return m[1];
  if (/^app\/core\//.test(p)) return 'core';
  if (/^app\/layout\//.test(p) || /^app\/app\.[^/]+$/.test(p) || /^(main|bootstrap)\.ts$/.test(p))
    return 'shell';
  return null;
}

/**
 * The placeholders a translation must keep: `{$NAME}` of templates and `$localize`, the ICU
 * variables (`{count, plural, ...}` -> `icu:count`) and the placeholders inside ICU cases
 * (`{INTERPOLATION}`, `{START_TAG_B}` ...). Returned sorted and de-duplicated.
 */
export function placeholdersOf(message) {
  const out = new Set();
  for (const m of message.matchAll(/\{\$([A-Za-z0-9_]+)\}/g)) out.add('$' + m[1]);
  for (const m of message.matchAll(/\{\s*([A-Za-z_][\w]*)\s*,\s*(plural|select|selectordinal)\s*,/g))
    out.add(`icu:${m[1]}`);
  for (const m of message.matchAll(
    /\{((?:INTERPOLATION|START_|CLOSE_|TAG_|LINE_BREAK|HORIZONTAL_RULE|PH|ICU)[A-Z0-9_]*)\}/g,
  ))
    out.add(m[1]);
  return [...out].sort();
}

/** Plural blocks of a message with their case keys: [{ variable, keys: ['one', 'other'] }]. */
export function pluralBlocks(message) {
  const blocks = [];
  const re = /\{\s*([A-Za-z_][\w]*)\s*,\s*(plural|selectordinal)\s*,/g;
  let m;
  while ((m = re.exec(message))) {
    const keys = [];
    let i = m.index + m[0].length;
    let depth = 1;
    let key = '';
    while (i < message.length && depth > 0) {
      const ch = message[i];
      if (ch === '{') {
        if (depth === 1) {
          keys.push(key.trim());
          key = '';
        }
        depth++;
      } else if (ch === '}') {
        depth--;
      } else if (depth === 1) {
        key += ch;
      }
      i++;
    }
    blocks.push({ variable: m[1], kind: m[2], keys });
  }
  return blocks;
}

/** Braces must balance (a translator dropping one breaks the ICU parser at runtime). */
export function bracesBalanced(message) {
  let depth = 0;
  for (const ch of message) {
    if (ch === '{') depth++;
    else if (ch === '}' && --depth < 0) return false;
  }
  return depth === 0;
}

/** Plural categories a language needs in every cardinal plural (`=N` cases are extra). */
export const PLURAL_CATEGORIES = {
  en: ['one', 'other'],
  pl: ['one', 'few', 'many', 'other'],
};

/**
 * Merges the per-area partial files into one translation map.
 * `partials`: { "<file name without .json>": { id: target } }.
 * Returns { translations, problems } where problems are duplicates, malformed ids and ids filed
 * under another area's file.
 */
export function mergePartials(partials) {
  const translations = {};
  const where = {};
  const problems = [];
  for (const [file, entries] of Object.entries(partials).sort(([a], [b]) => a.localeCompare(b))) {
    if (!entries || typeof entries !== 'object' || Array.isArray(entries)) {
      problems.push({ kind: 'invalid-file', area: file, detail: 'expected a flat JSON object' });
      continue;
    }
    for (const [id, target] of Object.entries(entries)) {
      if (typeof target !== 'string') {
        problems.push({ kind: 'invalid-target', area: file, id, detail: 'target must be a string' });
        continue;
      }
      const area = areaOfId(id);
      if (!area) {
        problems.push({ kind: 'malformed-id', area: file, id, detail: 'expected <area>.<screen>.<name>' });
      } else if (area !== file) {
        problems.push({
          kind: 'wrong-file',
          area: file,
          id,
          detail: `belongs in ${area}.json`,
        });
      }
      if (id in translations) {
        problems.push({ kind: 'duplicate', area: file, id, detail: `also in ${where[id]}.json` });
        continue;
      }
      translations[id] = target;
      where[id] = file;
    }
  }
  return { translations, where, problems };
}

/**
 * The gate. Inputs:
 *   source:   { id: English message } as extracted by `ng extract-i18n --format json`;
 *   partials: { area: { id: target } } (src/locale/<lang>/*.json);
 *   pending:  areas whose translation is not done yet (src/locale/pending-areas.json);
 *   areas:    every known area (shell, core, feature folders);
 *   scan:     { noId: [{file, line, text, area}], untagged: [{file, line, text, area, kind}],
 *               files: { id: [file, ...] } } from the source scanner (optional);
 *   lang:     target language (plural categories).
 * Returns { problems: [{kind, area, id?, file?, line?, detail}], stats: { area: {...} } }.
 * Every problem fails the gate; `pending` only relaxes "missing" and "untagged".
 */
export function checkTranslations({ source, partials, pending = [], areas = [], scan = {}, lang = 'pl' }) {
  const pendingSet = new Set(pending);
  const known = new Set([...areas, ...Object.keys(partials)]);
  const problems = [];
  const stats = {};
  const stat = (area) =>
    (stats[area] ??= { messages: 0, translated: 0, missing: 0, untagged: 0, pending: pendingSet.has(area) });

  for (const a of areas) stat(a);
  for (const a of pending)
    if (areas.length && !areas.includes(a))
      problems.push({ kind: 'unknown-pending-area', area: a, detail: 'not an area of src/app' });

  const merged = mergePartials(partials);
  problems.push(...merged.problems);
  const targets = merged.translations;

  // (a) messages without a custom id
  const autoIds = Object.keys(source).filter(isAutoId);
  if (scan.noId?.length) {
    for (const n of scan.noId)
      problems.push({ kind: 'no-custom-id', area: n.area ?? '?', file: n.file, line: n.line, detail: n.text });
  } else {
    for (const id of autoIds)
      problems.push({ kind: 'no-custom-id', area: '?', id, detail: source[id] });
  }

  for (const [id, text] of Object.entries(source)) {
    if (isAutoId(id)) continue;
    const area = areaOfId(id);
    if (!area || !known.has(area)) {
      problems.push({
        kind: 'malformed-id',
        area: area ?? '?',
        id,
        detail: area ? `unknown area "${area}"` : 'expected <area>.<screen>.<name>',
      });
      continue;
    }
    const files = scan.files?.[id];
    if (files) {
      const wrong = files.filter((f) => f.area && f.area !== area);
      for (const f of wrong)
        problems.push({
          kind: 'id-area-mismatch',
          area,
          id,
          file: f.file,
          detail: `the file belongs to area "${f.area}"`,
        });
    }
    const s = stat(area);
    s.messages++;
    // (b) missing
    if (!(id in targets)) {
      s.missing++;
      if (!pendingSet.has(area)) problems.push({ kind: 'missing', area, id, detail: text });
      continue;
    }
    s.translated++;
    const target = targets[id];
    // (c) placeholders
    const want = placeholdersOf(text);
    const got = placeholdersOf(target);
    if (want.join(' ') !== got.join(' ')) {
      const lost = want.filter((p) => !got.includes(p));
      const extra = got.filter((p) => !want.includes(p));
      problems.push({
        kind: 'placeholders',
        area,
        id,
        detail:
          (lost.length ? `missing ${lost.join(', ')}` : '') +
          (lost.length && extra.length ? '; ' : '') +
          (extra.length ? `unknown ${extra.join(', ')}` : ''),
      });
    }
    if (!bracesBalanced(target)) problems.push({ kind: 'braces', area, id, detail: 'unbalanced { }' });
    const needed = PLURAL_CATEGORIES[lang];
    if (needed) {
      for (const b of pluralBlocks(target)) {
        if (b.kind !== 'plural') continue;
        const missingCats = needed.filter((c) => !b.keys.includes(c));
        if (missingCats.length)
          problems.push({
            kind: 'plural-forms',
            area,
            id,
            detail: `{${b.variable}, plural} needs ${missingCats.join(', ')} in ${lang}`,
          });
      }
    }
    if (!target.trim()) problems.push({ kind: 'empty', area, id, detail: 'empty translation' });
  }

  // (d) stale
  for (const id of Object.keys(targets))
    if (!(id in source))
      problems.push({ kind: 'stale', area: merged.where[id], id, detail: 'no longer in the source' });

  for (const u of scan.untagged ?? []) {
    const area = u.area ?? '?';
    stat(area).untagged++;
    if (!pendingSet.has(area))
      problems.push({ kind: 'untagged', area, file: u.file, line: u.line, detail: u.text });
  }

  return { problems, stats };
}

/** "projects.detail.title" ids missing a translation in one area, with the English source. */
export function missingForArea(source, partials, area) {
  const { translations } = mergePartials(partials);
  const out = {};
  for (const [id, text] of Object.entries(source))
    if (areaOfId(id) === area && !(id in translations)) out[id] = text;
  return out;
}

/** Human-readable report lines. */
export function formatReport({ problems, stats }, { area: only } = {}) {
  const lines = [];
  const rows = Object.entries(stats)
    .filter(([a]) => !only || a === only)
    .sort(([a], [b]) => a.localeCompare(b));
  lines.push('area            messages  translated  missing  untagged  status');
  for (const [a, s] of rows) {
    lines.push(
      `${a.padEnd(15)} ${String(s.messages).padStart(8)}  ${String(s.translated).padStart(10)}  ${String(
        s.missing,
      ).padStart(7)}  ${String(s.untagged).padStart(8)}  ${s.pending ? 'pending' : 'checked'}`,
    );
  }
  const shown = problems.filter((p) => !only || p.area === only);
  if (shown.length) {
    lines.push('', `${shown.length} problem(s):`);
    const byKind = new Map();
    for (const p of shown) {
      if (!byKind.has(p.kind)) byKind.set(p.kind, []);
      byKind.get(p.kind).push(p);
    }
    for (const [kind, list] of byKind) {
      lines.push(`  ${kind} (${list.length}) - ${KIND_HELP[kind] ?? ''}`);
      for (const p of list.slice(0, 40)) {
        const where = p.file ? `${p.file}${p.line ? ':' + p.line : ''}` : '';
        lines.push(
          `    [${p.area}] ${p.id ?? ''}${p.id && where ? ' ' : ''}${where}${p.detail ? '  ' + truncate(p.detail) : ''}`,
        );
      }
      if (list.length > 40) lines.push(`    ... ${list.length - 40} more`);
    }
  }
  return lines;
}

export const KIND_HELP = {
  'duplicate-source-id':
    'one @@id is used for texts that differ (whitespace counts); give each place its own id',
  'no-custom-id': 'add i18n="@@<area>.<screen>.<name>" / $localize`:@@id:...`; an ICU must be the only content of its i18n element',
  missing: 'add the id to src/locale/pl/<area>.json',
  placeholders: 'the target must keep the placeholders of the source',
  braces: 'ICU braces do not balance',
  'plural-forms': 'Polish plurals need one / few / many / other',
  stale: 'remove the id from the partial file (the source no longer has it)',
  duplicate: 'an id may appear in one partial file only',
  'wrong-file': 'move the id to the file of its area',
  'malformed-id': 'ids are <area>.<screen>.<name> with a known area',
  'id-area-mismatch': 'the id prefix must be the area of the file that uses it',
  untagged: 'user-visible text without i18n / $localize (or add the words to src/locale/untranslatable.json, or // i18n-ignore)',
  empty: 'empty translation',
  'invalid-file': 'partial files are flat JSON objects',
  'invalid-target': 'targets are strings',
  'unknown-pending-area': 'remove it from src/locale/pending-areas.json',
};

function truncate(s) {
  const one = String(s).replace(/\s+/g, ' ');
  return one.length > 90 ? one.slice(0, 87) + '...' : one;
}

# 0026 - UI internationalisation: Angular's runtime `$localize`, Polish next to English

Status: accepted - 2026-10-07

## Context
The users of Back Office work in Polish and English. The UI was English only; the user manual is
being translated (`docs/manual/pl`, same slugs and anchors as `docs/manual`). We want a Polish UI that
the user can switch to without a second deployment, translated by several people (and agents) in
parallel without merge conflicts, with a safety net that catches untranslated text, broken
placeholders and wrong Polish plurals before a release. The initial bundle budget (300 kB gzip,
ADR 0022) must hold.

## Decision
1. **Angular's own i18n (`@angular/localize`) with runtime translation loading.** One build serves all
   languages. `main.ts` resolves the language before Angular starts (`localStorage` `bo.locale`, else
   Polish for a `pl*` browser, else English), fetches `/i18n/messages.<lang>.json`, calls
   `loadTranslations()`, registers the Angular locale data and only then imports the app
   (`bootstrap.ts`, `LOCALE_ID`). Switching the language stores the choice and reloads the page.
2. **Stable custom ids** on every message: `i18n="@@<area>.<screen>.<name>"` in templates,
   `$localize\`:@@id:English\`` in TypeScript; the area is the feature folder (`core`, `shell` for the
   layout and bootstrap). Auto-generated ids are rejected.
3. **ICU for plurals** (`{count, plural, one {...} few {...} many {...} other {...}}`); TypeScript
   messages use the same ICU text through the small `icu()` helper because `$localize` does not
   evaluate ICU.
4. **Translations per area** in `frontend/src/locale/<lang>/<area>.json` (flat `{id: target}`), merged by
   `npm run i18n:merge` into the generated, git-ignored `public/i18n/messages.<lang>.json`.
5. **A gate**, `npm run i18n:check` (part of `npm run build`): extracts with `ng extract-i18n`, scans
   the sources with Angular's template parser and the TypeScript compiler and fails on missing custom
   ids, missing translations, placeholder mismatches, Polish plurals without one/few/many/other, stale
   or duplicate ids and untagged user-visible text. Areas not translated yet are listed in
   `src/locale/pending-areas.json` and only relaxed for "missing" and "untagged".
6. **The manual follows the UI language**: the help service reads `/manual/pl/...` and falls back to the
   English chapter per file.

## Alternatives
- **ngx-translate / Transloco** (runtime key lookup, `{{ 'key' | translate }}`): switches without a
  reload and is popular, but it is a third-party layer over what Angular ships, keeps the English text
  out of the templates (keys only, so templates become harder to read and review), offers no extraction
  of what is actually used, and its pipes run on every change detection. Angular's i18n keeps the
  English source in place, extracts it with the CLI, compiles templates with the translation (no pipes)
  and handles ICU plurals natively.
- **Build per locale** (`ng build --localize`, the Angular default): fastest at runtime and no
  translations in the browser, but one bundle and one nginx route per language, a page reload to a
  different base href to switch, and a build that grows with every language. One image serving every
  language is simpler to deploy and operate.
- **Translation-only JSON without custom ids** (Angular's generated digests): ids change when the
  English text changes, which loses translations silently and makes parallel work on partial files
  impossible.

## Consequences
- Switching the language reloads the page (translated templates and module-level `$localize`
  constants are evaluated once). Acceptable for a preference that changes rarely.
- `main.ts` must never import app code statically; module-level `$localize` constants are safe
  everywhere else. `@angular/localize/init` is a polyfill (about 1 kB); the `:@@id:` metadata of each
  message stays in the bundle (the initial bundle grew from 259 kB to 271 kB gzip, budget 300 kB).
- English remains the source language; a missing translation shows English, never an id.
- Feature areas are translated one by one by removing them from `pending-areas.json`; from then on the
  gate keeps them complete. Terms follow `docs/ui/i18n-glossary.md`.
- Adding a language: a folder under `src/locale/`, an entry in `SUPPORTED_LOCALES`, the Angular locale
  data in `main.ts`, the NG-ZORRO locale in `core/ui/nz-locale.ts`, plural categories in
  `scripts/i18n-lib.mjs`, optionally `docs/manual/<lang>/`.

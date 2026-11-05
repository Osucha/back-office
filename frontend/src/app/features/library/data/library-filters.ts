/** Client-side filters and labels of the Library lists (pure; tested in library-filters.spec.ts). */
import type { Rule, Skill, TaskTemplate } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { fixed } from '../../../core/i18n/numbers';
import { executorLabel } from '../../../core/ui/executors';
import { categoryLabel } from '../../../core/ui/template-categories';
import { isDeprecated } from '../../../core/ui/template-tags';

/** ADR 0054: the shared executor names (core/ui/executors). */
export { executorLabel };

function matches(q: string, ...fields: (string | null | undefined | readonly string[])[]): boolean {
  return fields.some((f) =>
    Array.isArray(f)
      ? f.some((x) => x.toLowerCase().includes(q))
      : typeof f === 'string' && f.toLowerCase().includes(q),
  );
}

/**
 * ADR 0055: the category of a template - its own, else the nearest ancestor's (a parent in the
 * template's project wins over the global one), else GENERAL.
 */
export function effectiveCategory(
  t: Pick<TaskTemplate, 'category' | 'extendsKey' | 'scope' | 'key'>,
  all: readonly TaskTemplate[],
): string {
  if (t.category) return t.category;
  const projectId = t.scope?.type === 'PROJECT' ? t.scope.projectId : null;
  const seen = new Set<string>(t.key ? [t.key] : []);
  let key = t.extendsKey ?? null;
  while (key && !seen.has(key)) {
    seen.add(key);
    const candidates = all.filter((x) => x.key === key);
    const parent =
      (projectId ? candidates.find((x) => x.scope?.projectId === projectId) : undefined) ??
      candidates.find((x) => x.scope?.type !== 'PROJECT') ??
      candidates[0];
    if (!parent) break;
    if (parent.category) return parent.category;
    key = parent.extendsKey ?? null;
  }
  return 'GENERAL';
}

/**
 * Templates matching the search (name, key, description, category, tag, executor label),
 * executor and category (ADR 0055, effective one); deprecated templates (ADR 0054) only with
 * `showDeprecated`.
 */
export function filterTemplates(
  list: readonly TaskTemplate[],
  opts: {
    query?: string | null;
    executor?: string | null;
    category?: string | null;
    showDeprecated?: boolean | null;
  },
): TaskTemplate[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  return list.filter((t) => {
    const category = effectiveCategory(t, list);
    return (
      (opts.showDeprecated || !isDeprecated(t)) &&
      (!opts.executor || t.executor === opts.executor) &&
      (!opts.category || category === opts.category) &&
      (!q ||
        matches(
          q,
          t.key,
          t.name,
          t.description,
          categoryLabel(category),
          t.tags ?? [],
          t.executor ? executorLabel(t.executor) : null,
        ))
    );
  });
}

/** The display name of a library item referred to by key (falls back to the key). */
export function nameOfKey(
  list: readonly { key?: string | null; name?: string | null }[],
  key: string | null | undefined,
): string {
  if (!key) return '';
  return list.find((x) => x.key === key)?.name || key;
}

/** Skills matching the search (key, name, description, tag). */
export function filterSkills(list: readonly Skill[], query: string | null | undefined): Skill[] {
  const q = (query ?? '').trim().toLowerCase();
  if (!q) return [...list];
  return list.filter((s) => matches(q, s.key, s.name, s.description, s.tags ?? []));
}

/** Rules matching the search (name, key, description, tag) and, with `automatic`, only those. */
export function filterRules(
  list: readonly Rule[],
  opts: { query?: string | null; automatic?: boolean | null },
): Rule[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  return list.filter(
    (r) =>
      (!opts.automatic || r.automatic) &&
      (!q || matches(q, r.key, r.name, r.description, r.tags ?? [])),
  );
}

/** "2.4k chars" for a skill body. */
export function sizeLabel(chars: number): string {
  if (chars < 1000)
    return icu($localize`:@@library.size.chars:{count, plural, other {# chars}}`, { count: chars });
  const value = fixed(chars / 1000, chars < 10000 ? 1 : 0);
  return $localize`:@@library.size.kChars:${value}:value:k chars`;
}

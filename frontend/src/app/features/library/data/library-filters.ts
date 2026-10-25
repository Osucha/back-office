/** Client-side filters and labels of the Library lists (pure; tested in library-filters.spec.ts). */
import type { Skill, TaskInterface, TaskTemplate } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { fixed } from '../../../core/i18n/numbers';
import { executorLabel } from '../../../core/ui/executors';
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
 * Templates matching the search (key, name, description, interface, tag, executor label) and
 * executor; deprecated templates (ADR 0054) only with `showDeprecated`.
 */
export function filterTemplates(
  list: readonly TaskTemplate[],
  opts: { query?: string | null; executor?: string | null; showDeprecated?: boolean | null },
): TaskTemplate[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  return list.filter(
    (t) =>
      (opts.showDeprecated || !isDeprecated(t)) &&
      (!opts.executor || t.executor === opts.executor) &&
      (!q ||
        matches(
          q,
          t.key,
          t.name,
          t.description,
          t.implements ?? [],
          t.tags ?? [],
          t.executor ? executorLabel(t.executor) : null,
        )),
  );
}

/** Interfaces matching the search (key, name, description, slot names, implementing templates). */
export function filterInterfaces(
  list: readonly TaskInterface[],
  query: string | null | undefined,
): TaskInterface[] {
  const q = (query ?? '').trim().toLowerCase();
  if (!q) return [...list];
  return list.filter((i) =>
    matches(
      q,
      i.key,
      i.name,
      i.description,
      (i.inputs ?? []).map((s) => s.name),
      (i.outputs ?? []).map((s) => s.name),
      i.implementedBy ?? [],
    ),
  );
}

/** Skills matching the search (key, name, description, tag). */
export function filterSkills(list: readonly Skill[], query: string | null | undefined): Skill[] {
  const q = (query ?? '').trim().toLowerCase();
  if (!q) return [...list];
  return list.filter((s) => matches(q, s.key, s.name, s.description, s.tags ?? []));
}

/** "2.4k chars" for a skill body. */
export function sizeLabel(chars: number): string {
  if (chars < 1000)
    return icu($localize`:@@library.size.chars:{count, plural, other {# chars}}`, { count: chars });
  const value = fixed(chars / 1000, chars < 10000 ? 1 : 0);
  return $localize`:@@library.size.kChars:${value}:value:k chars`;
}

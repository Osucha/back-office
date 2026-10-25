/**
 * Templates the designer offers for new steps (pure; tested in palette-tasks.spec.ts).
 * ADR 0054: deprecated templates are never offered (known by key from the library list, or by a
 * `tags` / `deprecated` field should the palette carry one); abstract templates are not in the
 * palette at all.
 */
import type { PaletteTask } from '../../../core/api/types';
import { executorLabel } from '../../../core/ui/executors';
import { isDeprecated } from '../../../core/ui/template-tags';

type MaybeTagged = PaletteTask & { tags?: string[] | null; deprecated?: boolean | null };

/** Whether a palette entry is deprecated. */
export function deprecatedPaletteTask(
  t: PaletteTask,
  deprecatedKeys: ReadonlySet<string> = new Set(),
): boolean {
  const tagged = t as MaybeTagged;
  return deprecatedKeys.has(t.key ?? '') || tagged.deprecated === true || isDeprecated(tagged);
}

/** The palette entries to offer: not deprecated and matching the query (name, key, interface, executor). */
export function paletteTasks(
  tasks: readonly PaletteTask[],
  query: string,
  deprecatedKeys: ReadonlySet<string> = new Set(),
): PaletteTask[] {
  const q = query.trim().toLowerCase();
  return tasks.filter(
    (t) =>
      !deprecatedPaletteTask(t, deprecatedKeys) &&
      (!q ||
        (t.name ?? '').toLowerCase().includes(q) ||
        (t.key ?? '').toLowerCase().includes(q) ||
        (t.interfaces ?? []).some((i) => i.toLowerCase().includes(q)) ||
        (!!t.executor && executorLabel(t.executor).toLowerCase().includes(q))),
  );
}

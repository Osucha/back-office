/**
 * ADR 0055: what kind of work a library template does. Groups the template list and the designer
 * palette; a template without a category (own or inherited) counts as General. Pure; tested in
 * template-categories.spec.ts.
 */
import { TEMPLATE_CATEGORIES, type TemplateCategory } from '../api/types';

export { TEMPLATE_CATEGORIES };
export type { TemplateCategory };

const labels = (): Record<TemplateCategory, string> => ({
  ANALYSIS: $localize`:@@core.templateCategory.analysis:Analysis`,
  SPECIFICATION: $localize`:@@core.templateCategory.specification:Specification`,
  PLANNING: $localize`:@@core.templateCategory.planning:Planning`,
  IMPLEMENTATION: $localize`:@@core.templateCategory.implementation:Implementation`,
  REVIEW: $localize`:@@core.templateCategory.review:Review`,
  JIRA: $localize`:@@core.templateCategory.jira:Jira`,
  OPERATIONS: $localize`:@@core.templateCategory.operations:Operations`,
  GENERAL: $localize`:@@core.templateCategory.general:General`,
});

/** The human name of a category; unknown values are shown as they are, none as General. */
export function categoryLabel(category: string | null | undefined): string {
  const all = labels();
  if (!category) return all.GENERAL;
  return all[category as TemplateCategory] ?? category;
}

/** Position of a category in the display order (unknown ones last, before General). */
export function categoryOrder(category: string | null | undefined): number {
  const i = TEMPLATE_CATEGORIES.indexOf((category || 'GENERAL') as TemplateCategory);
  return i < 0 ? TEMPLATE_CATEGORIES.length - 1.5 : i;
}

/** Items grouped by category in display order; empty groups are left out. */
export function groupByCategory<T>(
  items: readonly T[],
  categoryOf: (item: T) => string | null | undefined,
): Array<{ category: string; label: string; items: T[] }> {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const c = categoryOf(item) || 'GENERAL';
    if (!groups.has(c)) groups.set(c, []);
    groups.get(c)!.push(item);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => categoryOrder(a) - categoryOrder(b))
    .map(([category, list]) => ({ category, label: categoryLabel(category), items: list }));
}

/**
 * Library tags with a meaning for the UI (ADR 0054). A template tagged `deprecated` should no
 * longer be chosen: lists hide it by default and the designer palette never offers it, while a
 * step that already uses it still shows (with a "Deprecated" tag).
 */

export const DEPRECATED_TAG = 'deprecated';

/** Text of the "Deprecated" tag shown next to such a template. */
export function deprecatedLabel(): string {
  return $localize`:@@core.template.deprecated:Deprecated`;
}

/** Tooltip of the "Deprecated" tag. */
export function deprecatedHint(): string {
  return $localize`:@@core.template.deprecated.hint:Kept for existing workflows; pick another template for new steps.`;
}

/** Whether the item carries the `deprecated` tag (case-insensitive). */
export function isDeprecated(
  item: { tags?: readonly string[] | null } | null | undefined,
): boolean {
  return (item?.tags ?? []).some((t) => t.toLowerCase() === DEPRECATED_TAG);
}

/** Keys of the deprecated items of a list. */
export function deprecatedKeys(
  list: readonly { key?: string | null; tags?: readonly string[] | null }[],
): Set<string> {
  return new Set(
    list
      .filter(isDeprecated)
      .map((t) => t.key ?? '')
      .filter((k) => !!k),
  );
}

/** The list without deprecated items, unless `show` is set. */
export function withoutDeprecated<T extends { tags?: readonly string[] | null }>(
  list: readonly T[],
  show = false,
): T[] {
  return show ? [...list] : list.filter((t) => !isDeprecated(t));
}

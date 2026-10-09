/** Form rules of the workflow library (ADR 0037): the entry key is a slug `^[a-z][a-z0-9-]{1,63}$`, unique. */

export const LIBRARY_KEY_PATTERN = /^[a-z][a-z0-9-]{1,63}$/;

/** A key suggested from a workflow name: "Issue to reviewed MR" -> "issue-to-reviewed-mr". */
export function suggestLibraryKey(name: string | null | undefined): string {
  const slug = (name ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/ł/g, 'l')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+/, '')
    .replace(/-+$/, '')
    .slice(0, 64)
    .replace(/-+$/, '');
  return /^[a-z]/.test(slug) ? slug : slug ? `wf-${slug}`.slice(0, 64) : '';
}

/** Why a key cannot be used, or null. */
export function libraryKeyError(key: string, taken: readonly string[]): string | null {
  if (!key) return $localize`:@@library.publish.keyRequired:Required`;
  if (!LIBRARY_KEY_PATTERN.test(key))
    return $localize`:@@library.publish.keySlug:Lowercase letters, digits and dashes, 2 to 64 characters, starting with a letter`;
  if (taken.includes(key))
    return $localize`:@@library.publish.keyTaken:This key is already used in the library`;
  return null;
}

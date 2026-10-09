/**
 * The user manual (`docs/manual/`, served at `/manual/`): the manifest types and small pure helpers
 * that the shell needs (kept tiny: this file is part of the initial bundle through HelpService).
 * Markdown processing lives in `manual-markdown.ts` (lazy chunks only).
 */

/** `docs/manual/manual.json`. */
export interface ManualManifest {
  title: string;
  description?: string;
  groups: ManualGroup[];
}

export interface ManualGroup {
  title: string;
  chapters: ManualChapter[];
}

export interface ManualChapter {
  slug: string;
  title: string;
  /** `<a id="..."></a>` anchors placed directly before a heading. */
  anchors: string[];
}

/** A place in the manual: a chapter, optionally one anchored section of it. */
export interface HelpRef {
  chapter: string;
  anchor?: string | null;
}

export const MANUAL_BASE = '/manual/';
export const MANIFEST_URL = `${MANUAL_BASE}manual.json`;

/**
 * Folder of a translation of the manual: English is the root (`docs/manual/`), other languages a
 * sub-folder with the same slugs and anchors (`docs/manual/pl/`, with its own `manual.json`).
 */
export function manualBase(locale = 'en'): string {
  return locale === 'en' ? MANUAL_BASE : `${MANUAL_BASE}${locale}/`;
}

export function manifestUrl(locale = 'en'): string {
  return `${manualBase(locale)}manual.json`;
}

export function chapterFileUrl(slug: string, locale = 'en'): string {
  return `${manualBase(locale)}${slug}.md`;
}

/** In-app link of a manual place: `/help/<chapter>#<anchor>`. */
export function manualLink(ref: HelpRef): string {
  return `/help/${ref.chapter}${ref.anchor ? '#' + ref.anchor : ''}`;
}

export function allChapters(m: ManualManifest | null | undefined): ManualChapter[] {
  return (m?.groups ?? []).flatMap((g) => g.chapters ?? []);
}

export function findChapter(
  m: ManualManifest | null | undefined,
  slug: string | null | undefined,
): ManualChapter | null {
  return allChapters(m).find((c) => c.slug === slug) ?? null;
}

/** Previous and next chapter in reading order (across groups). */
export function neighbours(
  m: ManualManifest | null | undefined,
  slug: string | null | undefined,
): { prev: ManualChapter | null; next: ManualChapter | null } {
  const list = allChapters(m);
  const i = list.findIndex((c) => c.slug === slug);
  if (i < 0) return { prev: null, next: null };
  return { prev: list[i - 1] ?? null, next: list[i + 1] ?? null };
}

/** Whether a chapter (and the anchor, when given) is listed in the manifest. */
export function refExists(m: ManualManifest, ref: HelpRef): boolean {
  const c = findChapter(m, ref.chapter);
  return !!c && (!ref.anchor || c.anchors.includes(ref.anchor));
}

const WORDS: Record<string, string> = {
  ci: 'CI',
  pat: 'PAT',
  mcp: 'MCP',
  llm: 'LLM',
  oidc: 'OIDC',
  mfa: 'MFA',
  ide: 'IDE',
  api: 'API',
  jql: 'JQL',
  gitlab: 'GitLab',
  github: 'GitHub',
  openai: 'OpenAI',
  vscode: 'VS Code',
  jira: 'Jira',
  keycloak: 'Keycloak',
  entra: 'Entra',
  google: 'Google',
  mistral: 'Mistral',
  ollama: 'Ollama',
  anthropic: 'Anthropic',
  cursor: 'Cursor',
  claude: 'Claude',
};

/** `jira-token` -> "Jira token", `ci-variables` -> "CI variables", `what-is` -> "What is". */
export function humanizeAnchor(id: string): string {
  const words = id.split(/[-_]+/).filter(Boolean);
  return words
    .map((w, i) => {
      const known = WORDS[w.toLowerCase()];
      if (known) return known;
      return i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w;
    })
    .join(' ');
}

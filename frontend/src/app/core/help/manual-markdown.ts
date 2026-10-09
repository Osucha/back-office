/**
 * Pure Markdown helpers for the manual (tested in manual-markdown.spec.ts): link rewriting for the
 * SPA, one section by anchor, and the client-side search index. Used by the help page and the help
 * panel (both lazy), never by the initial bundle.
 */
import { MANUAL_BASE } from './manual';

const FENCE = /^\s{0,3}(```|~~~)/;
const HEADING = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;

/** An `<a ...>` tag of a line: its `id`/`name` value and whether only `</a>` follows (`<a id="x"></a>`). */
interface AnchorTag {
  id: string | null;
  empty: boolean;
  /** Index after the tag (after `</a>` for an empty anchor). */
  end: number;
}

/** HTML whitespace (space, tab, line feed, carriage return, form feed). */
const isSpace = (c: string | undefined): boolean =>
  c !== undefined && c.length === 1 && ' \t\n\r\f'.includes(c);

function skipSpaces(s: string, i: number): number {
  let j = i;
  while (j < s.length && isSpace(s[j])) j++;
  return j;
}

/** End of an attribute name starting at `i` (stops at whitespace or `=`). */
function nameEnd(attrs: string, i: number): number {
  let j = i;
  while (j < attrs.length && !isSpace(attrs[j]) && attrs[j] !== '=') j++;
  return j;
}

/**
 * The attribute value starting at `i` (just after `=` and spaces): the quoted text and the index after
 * it, or `value: null` for an unquoted value; `end: -1` when a quote is never closed.
 */
function readValue(attrs: string, i: number): { value: string | null; end: number } {
  const quote = attrs[i];
  if (quote === '"' || quote === "'") {
    const close = attrs.indexOf(quote, i + 1);
    return close < 0
      ? { value: null, end: -1 }
      : { value: attrs.slice(i + 1, close), end: close + 1 };
  }
  let j = i;
  while (j < attrs.length && !isSpace(attrs[j])) j++;
  return { value: null, end: j };
}

/**
 * The quoted value of the first `id` or `name` attribute in the attribute text of a tag. A small
 * scanner instead of a regular expression: one pass, no backtracking (SonarQube S5852).
 */
function idAttribute(attrs: string): string | null {
  let i = 0;
  while (i < attrs.length) {
    const nameStart = skipSpaces(attrs, i);
    const afterName = nameEnd(attrs, nameStart);
    const eq = skipSpaces(attrs, afterName);
    if (attrs[eq] !== '=') {
      // an attribute without a value, or a stray character: move on
      i = eq === nameStart ? eq + 1 : eq;
      continue;
    }
    const { value, end } = readValue(attrs, skipSpaces(attrs, eq + 1));
    if (end < 0) return null;
    const name = attrs.slice(nameStart, afterName).toLowerCase();
    if ((name === 'id' || name === 'name') && value) return value;
    i = end;
  }
  return null;
}

/**
 * The `<a ...>` tag starting at `at` (where `lower` has `<a`), or null when it is not an anchor tag
 * (`<abbr>`, a bare `<a>`). A tag ends at the first `>`.
 */
function anchorTagAt(line: string, lower: string, at: number): AnchorTag | null {
  if (!isSpace(line[at + 2])) return null;
  const close = line.indexOf('>', at + 2);
  // Never closed: no later tag can close either, so it swallows the rest of the line (linear).
  if (close < 0) return { id: null, empty: false, end: line.length };
  const id = idAttribute(line.slice(at + 2, close));
  const after = skipSpaces(line, close + 1);
  const empty = lower.startsWith('</a>', after);
  return { id, empty, end: empty ? after + 4 : close + 1 };
}

/** All `<a ...>` tags of a line, left to right; each character is looked at a bounded number of times. */
function anchorTags(line: string): AnchorTag[] {
  const lower = line.toLowerCase();
  const tags: AnchorTag[] = [];
  let at = lower.indexOf('<a');
  while (at >= 0) {
    const tag = anchorTagAt(line, lower, at);
    if (tag) tags.push(tag);
    at = lower.indexOf('<a', tag ? tag.end : at + 2);
  }
  return tags;
}

/** Whether the line contains an `<a>` tag with `id` or `name` equal to `anchor`. */
function hasAnchor(line: string, anchor: string): boolean {
  return anchorTags(line).some((t) => t.id === anchor);
}

/** The id of the first empty anchor (`<a id="x"></a>`) of the line. */
function firstEmptyAnchor(line: string): string | null {
  return anchorTags(line).find((t) => t.empty && t.id)?.id ?? null;
}

/** Whether the line holds nothing but empty anchors with an id (and whitespace). */
function isAnchorOnlyLine(line: string): boolean {
  const lower = line.toLowerCase();
  let i = skipSpaces(line, 0);
  if (i >= line.length) return false;
  while (i < line.length) {
    const tag = lower.startsWith('<a', i) ? anchorTagAt(line, lower, i) : null;
    if (!tag?.empty || !tag.id) return false;
    i = skipSpaces(line, tag.end);
  }
  return true;
}

interface Line {
  text: string;
  /** Inside a fenced code block (fence lines included). */
  code: boolean;
  heading: { level: number; title: string } | null;
}

function scan(markdown: string): Line[] {
  let inFence = false;
  return markdown.split(/\r?\n/).map((text) => {
    if (FENCE.test(text)) {
      inFence = !inFence;
      return { text, code: true, heading: null };
    }
    if (inFence) return { text, code: true, heading: null };
    const m = HEADING.exec(text);
    return {
      text,
      code: false,
      heading: m ? { level: m[1].length, title: plainHeading(m[2]) } : null,
    };
  });
}

/** Heading text without inline HTML (anchors), emphasis and code marks. */
export function plainHeading(raw: string): string {
  return raw
    .replace(/<[^<>]+>/g, '')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[*_`]/g, '')
    .trim();
}

/**
 * Rewrites link targets of a manual chapter for the SPA:
 * `other.md#x` -> `/help/other#x`, `#x` -> `/help/<current>#x`, relative files (images) ->
 * `/manual/<file>`. Absolute URLs, `/paths` and `mailto:` stay. Code blocks are left alone.
 */
export function rewriteManualLinks(markdown: string, currentSlug: string): string {
  const target = (dest: string): string => {
    if (/^[a-z][a-z0-9+.-]*:/i.test(dest) || dest.startsWith('/')) return dest;
    if (dest.startsWith('#')) return `/help/${currentSlug}${dest}`;
    const m = /^(?:\.\/)?([\w-]+)\.md(#[\w-]+)?$/.exec(dest);
    if (m) return `/help/${m[1]}${m[2] ?? ''}`;
    return MANUAL_BASE + dest.replace(/^\.\//, '');
  };
  return scan(markdown)
    .map((l) =>
      l.code
        ? l.text
        : l.text
            // inline links and images: ](dest) / ](dest "title") / ](<dest>)
            .replace(
              /\]\(\s*<?([^)\s>]+)>?(\s+"[^"]*")?\s*\)/g,
              (_all, dest: string, title: string | undefined) => `](${target(dest)}${title ?? ''})`,
            )
            // reference definitions: [id]: dest
            .replace(
              /^(\s{0,3}\[[^\]]+\]:\s*)(\S+)/,
              (_all, head: string, dest: string) => head + target(dest),
            )
            // raw HTML attributes
            .replace(
              /\b(href|src)=(["'])([^"']+)\2/g,
              (_all, attr: string, q: string, dest: string) => `${attr}=${q}${target(dest)}${q}`,
            ),
    )
    .join('\n');
}

export interface ManualSection {
  title: string;
  level: number;
  /** The Markdown under the heading (the heading itself excluded). */
  body: string;
}

/**
 * The section that starts at `<a id="anchor"></a>`: from its heading to the next heading of the
 * same or a higher level (any anchor lines right before that heading belong to the next section).
 * Null when the anchor or its heading is missing.
 */
export function extractSection(markdown: string, anchor: string): ManualSection | null {
  const lines = scan(markdown);
  const start = lines.findIndex((l) => !l.code && hasAnchor(l.text, anchor));
  if (start < 0) return null;
  const h = lines.findIndex((l, i) => i >= start && !!l.heading);
  if (h < 0) return null;
  // Only blank or anchor lines may sit between the anchor and its heading.
  for (let i = start + 1; i < h; i++)
    if (lines[i].text.trim() && !isAnchorOnlyLine(lines[i].text)) return null;
  const level = lines[h].heading!.level;
  let end = lines.findIndex((l, i) => i > h && !!l.heading && l.heading.level <= level);
  if (end < 0) end = lines.length;
  else end = trimTrailingAnchors(lines, h + 1, end);
  return {
    title: lines[h].heading!.title,
    level,
    body: lines
      .slice(h + 1, end)
      .map((l) => l.text)
      .join('\n')
      .trim(),
  };
}

/** The chapter's introduction: its first heading (title) and the text before the next heading. */
export function chapterIntro(markdown: string): ManualSection | null {
  const lines = scan(markdown);
  const h = lines.findIndex((l) => !!l.heading);
  if (h < 0) return null;
  let end = lines.findIndex((l, i) => i > h && !!l.heading);
  if (end < 0) end = lines.length;
  else end = trimTrailingAnchors(lines, h + 1, end);
  return {
    title: lines[h].heading!.title,
    level: lines[h].heading!.level,
    body: lines
      .slice(h + 1, end)
      .map((l) => l.text)
      .join('\n')
      .trim(),
  };
}

function trimTrailingAnchors(lines: Line[], from: number, end: number): number {
  let e = end;
  while (e > from && (!lines[e - 1].text.trim() || isAnchorOnlyLine(lines[e - 1].text))) e--;
  return e;
}

/** Whether a fetched file is real Markdown (an SPA fallback answers missing files with HTML). */
export function isMarkdownResponse(text: string | null | undefined): text is string {
  if (!text || !text.trim()) return false;
  return !/^\s*(<!doctype html|<html[\s>])/i.test(text);
}

// ------------------------------------------------------------------------------------- search

export interface SearchSection {
  chapter: string;
  chapterTitle: string;
  /** Anchor to link to: the section's own anchor or the enclosing anchored section's. */
  anchor: string | null;
  title: string;
  text: string;
}

export interface SearchHit {
  section: SearchSection;
  score: number;
  snippet: string;
  link: string;
}

/** Plain text of Markdown for searching: no code fences, HTML, link targets or emphasis marks. */
export function plainText(markdown: string): string {
  return markdown
    .replace(/<[^<>]+>/g, ' ')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}(```|~~~).*$/gm, ' ')
    .replace(/^\s{0,3}(>|[-*+]|\d+\.)\s+/gm, '')
    .replace(/[*_`|]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Splits chapters into searchable sections at headings of level 2 and below. */
export function buildSearchIndex(
  chapters: Array<{ slug: string; title: string; markdown: string }>,
): SearchSection[] {
  return chapters.flatMap((c) => {
    const splitter = new SectionSplitter(c.slug, c.title);
    for (const l of scan(c.markdown)) splitter.add(l);
    return splitter.finish();
  });
}

/**
 * Splits one chapter into sections. An anchor-only line names the next heading's section; sections
 * without an anchor of their own link to the enclosing anchored section (reset by a level-1
 * heading). The chapter title (the first level-1 heading) starts no section.
 */
class SectionSplitter {
  private readonly out: SearchSection[] = [];
  private current: SearchSection;
  private buffer: string[] = [];
  private pendingAnchor: string | null = null;
  private lastAnchor: string | null = null;
  private seenTitle = false;

  constructor(
    private readonly slug: string,
    private readonly chapterTitle: string,
  ) {
    this.current = this.section(null, chapterTitle);
  }

  add(l: Line): void {
    if (!l.code && this.isStructure(l)) return;
    this.buffer.push(l.text);
  }

  finish(): SearchSection[] {
    this.flush();
    return this.out;
  }

  /** Handles anchors and headings; true when the line is not section text. */
  private isStructure(l: Line): boolean {
    const a = firstEmptyAnchor(l.text);
    if (a && isAnchorOnlyLine(l.text)) {
      this.pendingAnchor = a;
      return true;
    }
    if (l.heading && (l.heading.level >= 2 || this.seenTitle)) {
      this.startSection(l.heading, a);
      return true;
    }
    if (l.heading) {
      // the chapter title
      this.seenTitle = true;
      this.pendingAnchor = null;
      return true;
    }
    if (l.text.trim()) this.pendingAnchor = null;
    return false;
  }

  private startSection(heading: { level: number; title: string }, inlineAnchor: string | null) {
    this.flush();
    this.seenTitle = true;
    const own = this.pendingAnchor ?? inlineAnchor;
    if (heading.level === 1) this.lastAnchor = null;
    if (own) this.lastAnchor = own;
    this.current = this.section(own ?? this.lastAnchor, heading.title);
    this.pendingAnchor = null;
  }

  private flush(): void {
    this.current.text = plainText(this.buffer.join('\n'));
    if (this.current.text || this.current.anchor) this.out.push(this.current);
    this.buffer = [];
  }

  private section(anchor: string | null, title: string): SearchSection {
    return { chapter: this.slug, chapterTitle: this.chapterTitle, anchor, title, text: '' };
  }
}

export function searchTerms(query: string): string[] {
  return [
    ...new Set(
      query
        .toLowerCase()
        .split(/[^\p{L}\p{N}_.-]+/u)
        .map((t) => t.replace(/^[.-]+|[.-]+$/g, ''))
        .filter((t) => t.length >= 2),
    ),
  ];
}

/** Sections containing every term, best first: title hits weigh more than body hits. */
export function searchManual(index: SearchSection[], query: string, limit = 30): SearchHit[] {
  const terms = searchTerms(query);
  if (!terms.length) return [];
  const hits: SearchHit[] = [];
  for (const s of index) {
    const score = sectionScore(s, terms);
    if (score === null) continue;
    hits.push({
      section: s,
      score,
      snippet: snippet(s.text, terms),
      link: `/help/${s.chapter}${s.anchor ? '#' + s.anchor : ''}`,
    });
  }
  return hits.sort((a, b) => b.score - a.score).slice(0, limit);
}

/**
 * Per term: 10 for the section title, 3 for the chapter title, one per body occurrence (at most 5);
 * null when a term occurs nowhere.
 */
function sectionScore(s: SearchSection, terms: readonly string[]): number | null {
  const title = s.title.toLowerCase();
  const chapter = s.chapterTitle.toLowerCase();
  const text = s.text.toLowerCase();
  let score = 0;
  for (const term of terms) {
    const inTitle = title.includes(term);
    const inChapter = chapter.includes(term);
    const count = occurrences(text, term);
    if (!inTitle && !inChapter && !count) return null;
    score += (inTitle ? 10 : 0) + (inChapter ? 3 : 0) + Math.min(count, 5);
  }
  return score;
}

function occurrences(text: string, term: string): number {
  let n = 0;
  for (let i = text.indexOf(term); i >= 0; i = text.indexOf(term, i + term.length)) n++;
  return n;
}

/** About 180 characters around the first term found, cut at word boundaries. */
export function snippet(text: string, terms: string[], width = 180): string {
  if (!text) return '';
  const lower = text.toLowerCase();
  const positions = terms.map((t) => lower.indexOf(t)).filter((i) => i >= 0);
  const at = positions.length ? Math.min(...positions) : 0;
  let from = Math.max(0, at - Math.floor(width / 3));
  let to = Math.min(text.length, from + width);
  if (from > 0) {
    const space = text.indexOf(' ', from);
    if (space >= 0 && space < at) from = space + 1;
  }
  if (to < text.length) {
    const space = text.lastIndexOf(' ', to);
    if (space > at) to = space;
  }
  return (from > 0 ? '…' : '') + text.slice(from, to).trim() + (to < text.length ? '…' : '');
}

/** Splits a snippet into plain and matching parts for highlighting (no HTML involved). */
export function highlightParts(
  text: string,
  terms: string[],
): Array<{ text: string; hit: boolean }> {
  if (!terms.length || !text) return [{ text, hit: false }];
  const esc = terms
    .slice()
    .sort((a, b) => b.length - a.length)
    .map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  const re = new RegExp(`(${esc.join('|')})`, 'gi');
  return text
    .split(re)
    .filter((p) => p !== '')
    .map((p) => ({ text: p, hit: terms.includes(p.toLowerCase()) }));
}

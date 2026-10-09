/**
 * A small fuzzy scorer for the command palette (no dependency). Case-insensitive subsequence
 * matching with bonuses for prefixes, word starts and consecutive characters; every space-separated
 * token of the query must match. Higher is better; `null` means no match.
 */

export interface FuzzyMatch {
  score: number;
  /** Matched character positions in `text` (for highlighting). */
  indices: number[];
}

const WORD_BREAK = /[\s\-_./:#·()[\]]/;

/** Scores one token against one text. */
export function scoreToken(token: string, text: string): FuzzyMatch | null {
  if (!token) return { score: 0, indices: [] };
  const t = text.toLowerCase();
  const q = token.toLowerCase();
  if (q.length > t.length) return null;

  const at = t.indexOf(q);
  return at >= 0 ? substringMatch(q, t, at) : subsequenceMatch(q, t);
}

const isWordStart = (t: string, i: number) => i === 0 || WORD_BREAK.test(t[i - 1]);

/** Exact substring at `at`: strongest signal, best when it starts the text or a word. */
function substringMatch(q: string, t: string, at: number): FuzzyMatch {
  const indices = Array.from({ length: q.length }, (_, i) => at + i);
  let score = 100 + q.length * 12;
  if (at === 0) score += 60;
  else if (isWordStart(t, at)) score += 35;
  if (q.length === t.length) score += 50;
  return { score: score - t.length * 0.2, indices };
}

/** Subsequence with greedy preference for word starts; null when scattered or incomplete. */
function subsequenceMatch(q: string, t: string): FuzzyMatch | null {
  const indices: number[] = [];
  let score = 0;
  let ti = 0;
  let prev = -2;
  for (const c of q.split('')) {
    const found = nextOccurrence(t, c, ti);
    if (found < 0) return null;
    score += 10;
    if (found === prev + 1) score += 15;
    if (isWordStart(t, found)) score += 20;
    score -= Math.min(found - ti, 6);
    indices.push(found);
    prev = found;
    ti = found + 1;
  }
  // Scattered letters across a long text are noise, not a match.
  if (indices[indices.length - 1] - indices[0] > q.length * 2 + 2) return null;
  if (indices[0] === 0) score += 15;
  return { score: score - t.length * 0.2, indices };
}

/**
 * Position of `c` in `t` from `from`: the next word-start occurrence when it lies within 3
 * characters of the next plain occurrence, otherwise that plain occurrence; -1 when absent.
 */
function nextOccurrence(t: string, c: string, from: number): number {
  let found = -1;
  for (let j = from; j < t.length; j++) {
    if (t[j] !== c) continue;
    if (found < 0) found = j;
    if (isWordStart(t, j) && j - found <= 3) return j;
    if (j - found > 3) break;
  }
  return found;
}

/**
 * Scores a query against an item's fields (title first, then keywords). Every token must match
 * some field; the item's score is the sum of each token's best field score, with the title
 * weighted higher. Highlight indices refer to the first field.
 */
export function fuzzyScore(
  query: string,
  fields: readonly (string | null | undefined)[],
): FuzzyMatch | null {
  const tokens = query.trim().split(/\s+/).filter(Boolean);
  if (!tokens.length) return { score: 0, indices: [] };
  let total = 0;
  const indices = new Set<number>();
  for (const token of tokens) {
    let best: { score: number; field: number; m: FuzzyMatch } | null = null;
    fields.forEach((f, i) => {
      if (!f) return;
      const m = scoreToken(token, f);
      if (!m) return;
      const weighted = i === 0 ? m.score : m.score * 0.7;
      if (!best || weighted > best.score) best = { score: weighted, field: i, m };
    });
    if (!best) return null;
    const b = best as { score: number; field: number; m: FuzzyMatch };
    total += b.score;
    if (b.field === 0) b.m.indices.forEach((x) => indices.add(x));
  }
  return { score: total, indices: [...indices].sort((a, b) => a - b) };
}

/** Filters and sorts `items` by the query; stable for equal scores. Empty query keeps the order. */
export function fuzzyFilter<T>(
  items: readonly T[],
  query: string,
  fields: (item: T) => readonly (string | null | undefined)[],
  limit = 50,
): Array<{ item: T; match: FuzzyMatch }> {
  const scored: Array<{ item: T; match: FuzzyMatch; i: number }> = [];
  items.forEach((item, i) => {
    const match = fuzzyScore(query, fields(item));
    if (match) scored.push({ item, match, i });
  });
  if (query.trim()) scored.sort((a, b) => b.match.score - a.match.score || a.i - b.i);
  return scored.slice(0, limit).map(({ item, match }) => ({ item, match }));
}

/** Splits `text` into highlighted / plain parts for rendering. */
export function highlightParts(
  text: string,
  indices: readonly number[],
): Array<{ text: string; hit: boolean }> {
  if (!indices.length) return [{ text, hit: false }];
  const set = new Set(indices);
  const out: Array<{ text: string; hit: boolean }> = [];
  for (let i = 0; i < text.length; i++) {
    const hit = set.has(i);
    const last = out[out.length - 1];
    if (last && last.hit === hit) last.text += text[i];
    else out.push({ text: text[i], hit });
  }
  return out;
}

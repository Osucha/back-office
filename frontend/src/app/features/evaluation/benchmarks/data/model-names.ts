/**
 * Short model names for the benchmark pages (pure; tested in model-names.spec.ts). What every name repeats is
 * factored out and shown once: a parenthesized suffix all names end with ("Claude Opus (my login)" -> "Claude Opus",
 * suffix "my login") and a leading word all names start with ("Claude Opus", "Claude Haiku" -> "Opus", "Haiku").
 * Nothing is removed when it would leave a name empty or make two different names equal.
 */
export interface ShortModelNames {
  /** The short name of each input, in the input order. */
  names: string[];
  /** The removed common suffix without its parentheses ("my login"), or null. */
  suffix: string | null;
  /** The removed common leading word ("Claude"), or null. */
  prefix: string | null;
}

const SUFFIX = /\s*\(([^()]+)\)\s*$/;

export function shortModelNames(names: readonly string[]): ShortModelNames {
  const distinct = [...new Set(names.map((n) => n.trim()).filter(Boolean))];
  const short = new Map(distinct.map((n) => [n, n]));
  let suffix: string | null = null;
  let prefix: string | null = null;
  if (distinct.length) {
    const matches = distinct.map((n) => SUFFIX.exec(n));
    const common = matches[0]?.[1]?.trim();
    if (common && matches.every((m) => m && m[1].trim() === common)) {
      const stripped = distinct.map((n) => n.replace(SUFFIX, '').trim());
      if (stripped.every(Boolean) && new Set(stripped).size === distinct.length) {
        distinct.forEach((n, i) => short.set(n, stripped[i]));
        suffix = common;
      }
    }
  }
  if (distinct.length >= 2) {
    const current = distinct.map((n) => short.get(n)!);
    const words = current.map((n) => n.split(/\s+/));
    const first = words[0][0];
    if (first && words.every((w) => w.length > 1 && w[0] === first)) {
      const stripped = words.map((w) => w.slice(1).join(' '));
      if (new Set(stripped).size === new Set(current).size) {
        distinct.forEach((n, i) => short.set(n, stripped[i]));
        prefix = first;
      }
    }
  }
  return { names: names.map((n) => short.get(n.trim()) ?? n), suffix, prefix };
}

/**
 * A name outside the set (the judge) shortened the same way: the removed suffix and leading word only when the name has
 * them, so "Claude Opus (my login)" reads "Opus" next to candidates whose suffix is said once in the header.
 */
export function applyShortName(
  name: string,
  short: Pick<ShortModelNames, 'suffix' | 'prefix'>,
): string {
  let out = name.trim();
  const m = SUFFIX.exec(out);
  if (short.suffix && m && m[1].trim() === short.suffix) out = out.replace(SUFFIX, '').trim();
  const words = out.split(/\s+/);
  if (short.prefix && words.length > 1 && words[0] === short.prefix) out = words.slice(1).join(' ');
  return out || name;
}

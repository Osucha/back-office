/**
 * Text from the server (gate questions, workflow names, issue keys) is untrusted: agents and issue
 * trackers can put anything into it (ADR 0029). Before it goes into a desktop notification it is
 * reduced to one plain line: control, bidi-override, zero-width and tag characters removed,
 * whitespace collapsed, length capped.
 */

// C0/C1 controls (incl. tab and line breaks), bidi marks / embeddings / overrides / isolates,
// zero-width characters, word joiner, BOM, interlinear annotation, Unicode tag characters.
// Built from code points so the source holds no invisible characters.
const UNSAFE_RANGES: readonly [number, number][] = [
  [0x0000, 0x001f],
  [0x007f, 0x009f],
  [0x061c, 0x061c],
  [0x200b, 0x200f],
  [0x202a, 0x202e],
  [0x2060, 0x2064],
  [0x2066, 0x206f],
  [0xfeff, 0xfeff],
  [0xfff9, 0xfffb],
];
const BACKSLASH = String.fromCharCode(92);
const hex = (c: number) => BACKSLASH + 'u' + c.toString(16).padStart(4, '0');
const UNSAFE = new RegExp(
  '[' +
    UNSAFE_RANGES.map(([a, b]) => (a === b ? hex(a) : hex(a) + '-' + hex(b))).join('') +
    ']|' +
    hex(0xdb40) +
    '[' +
    hex(0xdc00) +
    '-' +
    hex(0xdc7f) +
    ']',
  'g',
);

/** One clean line of at most `max` characters (an ellipsis marks a cut). */
export function cleanText(value: unknown, max = 120): string {
  if (typeof value !== 'string') return '';
  const line = value.replace(UNSAFE, ' ').replace(/\s+/g, ' ').trim();
  if (line.length <= max) return line;
  // Do not cut a surrogate pair in half.
  let cut = Math.max(0, max - 1);
  const code = line.charCodeAt(cut - 1);
  if (code >= 0xd800 && code <= 0xdbff) cut -= 1;
  return line.slice(0, cut).trimEnd() + '…';
}

/** Clean parts joined with " · ", empty parts dropped. */
export function joinClean(parts: readonly unknown[], max = 160, partMax = 80): string {
  return cleanText(
    parts
      .map((p) => cleanText(p, partMax))
      .filter(Boolean)
      .join(' · '),
    max,
  );
}

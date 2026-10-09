/**
 * Pure editing rules of `bo-code-input` (a fixed number of single-digit cells). Every function takes
 * the current cells and returns the new cells plus the index that should get focus, so the component
 * only renders and moves focus.
 */
export interface CodeEdit {
  cells: string[];
  /** Cell to focus after the edit (clamped to the last cell). */
  focus: number;
}

export function emptyCells(length: number): string[] {
  return Array.from({ length }, () => '');
}

/** Splits a value (e.g. from `[(value)]`) into cells; non-digits are dropped. */
export function cellsOf(value: string | null | undefined, length: number): string[] {
  const digits = onlyDigits(value ?? '').slice(0, length);
  return emptyCells(length).map((_, i) => digits[i] ?? '');
}

export function onlyDigits(text: string): string {
  return text.replace(/\D+/g, '');
}

/** The code when every cell is filled, otherwise null. */
export function completeCode(cells: readonly string[]): string | null {
  return cells.every((c) => /^\d$/.test(c)) ? cells.join('') : null;
}

/**
 * Text typed into cell `index` (usually one character; several when the browser autofills the
 * one-time code into the first cell). Digits fill from `index` onwards; focus moves past the last
 * written cell. Non-digits are ignored and keep the cell as it was.
 */
export function applyInput(cells: readonly string[], index: number, text: string): CodeEdit {
  const digits = onlyDigits(text);
  const next = [...cells];
  if (!digits) return { cells: next, focus: index };
  // A full code typed or autofilled anywhere replaces the whole code.
  if (digits.length >= cells.length) return fill(cells.length, digits);
  let i = index;
  for (const d of digits) {
    if (i >= next.length) break;
    next[i++] = d;
  }
  return { cells: next, focus: Math.min(i, next.length - 1) };
}

/** Paste: a code of the full length replaces everything, a shorter one fills from `index`. */
export function applyPaste(cells: readonly string[], index: number, text: string): CodeEdit {
  const digits = onlyDigits(text);
  if (digits.length >= cells.length) return fill(cells.length, digits);
  return applyInput(cells, index, digits);
}

/**
 * Backspace in cell `index`: clears the cell when it has a digit (focus stays); on an empty cell it
 * clears the previous cell and moves there.
 */
export function applyBackspace(cells: readonly string[], index: number): CodeEdit {
  const next = [...cells];
  if (next[index]) {
    next[index] = '';
    return { cells: next, focus: index };
  }
  const prev = Math.max(0, index - 1);
  next[prev] = '';
  return { cells: next, focus: prev };
}

/** Delete: clears the cell, focus stays. */
export function applyDelete(cells: readonly string[], index: number): CodeEdit {
  const next = [...cells];
  next[index] = '';
  return { cells: next, focus: index };
}

/** The cell a click or focus should land on: the first empty one (never skip ahead of a gap). */
export function firstEmpty(cells: readonly string[]): number {
  const i = cells.findIndex((c) => !c);
  return i === -1 ? cells.length - 1 : i;
}

function fill(length: number, digits: string): CodeEdit {
  const cells = emptyCells(length).map((_, i) => digits[i] ?? '');
  return { cells, focus: length - 1 };
}

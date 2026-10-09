import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

/**
 * A keyboard shortcut. `keys` is one combination: a key (`j`, `?`, `.`, `/`, `Enter`, `Escape`)
 * optionally prefixed with modifiers: `mod+` (Ctrl, or Cmd on macOS), `shift+`, `alt+`.
 */
export interface Shortcut {
  keys: string;
  description: string;
  /** Heading in the `?` overlay ("Global", "List", "Gate", ...). */
  group?: string;
  handler: (event: KeyboardEvent) => void;
  /** Also fire while typing in an input (e.g. `mod+s`, `Escape`). Default false. */
  allowInInputs?: boolean;
  /**
   * Evaluated at key time (with the event) and for the overlay (without); the shortcut is skipped
   * and hidden when false.
   */
  when?: (event?: KeyboardEvent) => boolean;
  /** Hide from the overlay (still active). */
  hidden?: boolean;
}

interface Registered extends Shortcut {
  id: number;
  combo: Combo;
}

interface Combo {
  key: string;
  mod: boolean;
  shift: boolean;
  alt: boolean;
}

export function parseKeys(keys: string): Combo {
  const parts = keys.split('+').map((p) => p.trim());
  // "mod++" style is not used; a literal "+" key is written "plus".
  const key = parts.pop() ?? '';
  const mods = new Set(parts.map((p) => p.toLowerCase()));
  return {
    key: key === 'plus' ? '+' : key.length === 1 ? key.toLowerCase() : key.toLowerCase(),
    mod: mods.has('mod') || mods.has('ctrl') || mods.has('cmd'),
    shift: mods.has('shift'),
    alt: mods.has('alt'),
  };
}

/** Symbols typed with Shift on common layouts: their Shift state is not compared. */
const SHIFTED_SYMBOLS = new Set([
  '?',
  '!',
  '@',
  '#',
  '$',
  '%',
  '^',
  '&',
  '*',
  '(',
  ')',
  '_',
  '+',
  ':',
  '"',
  '<',
  '>',
  '{',
  '}',
  '|',
  '~',
]);

export function matches(
  combo: Combo,
  e: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'shiftKey' | 'altKey'>,
): boolean {
  const key = (e.key ?? '').length === 1 ? e.key.toLowerCase() : (e.key ?? '').toLowerCase();
  if (key !== combo.key) return false;
  const mod = e.ctrlKey || e.metaKey;
  if (mod !== combo.mod) return false;
  if (!!e.altKey !== combo.alt) return false;
  if (!SHIFTED_SYMBOLS.has(combo.key) && combo.key.length === 1 && !!e.shiftKey !== combo.shift)
    return false;
  return true;
}

/** Typing targets: shortcuts without `allowInInputs` never fire from these. */
export function isTypingTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el || typeof el.closest !== 'function') return false;
  if (el.isContentEditable) return true;
  return !!el.closest(
    'input, textarea, select, [contenteditable=""], [contenteditable="true"], [role="textbox"], [role="combobox"], .ant-select, nz-select',
  );
}

export function isMac(): boolean {
  const p = typeof navigator !== 'undefined' ? navigator.platform || navigator.userAgent : '';
  return /Mac|iPhone|iPad/i.test(p);
}

/** Human form of a combination: `mod+k` -> "⌘ K" on macOS, "Ctrl K" elsewhere. */
export function formatKeys(keys: string, mac = isMac()): string[] {
  const c = parseKeys(keys);
  const out: string[] = [];
  if (c.mod) out.push(mac ? '⌘' : 'Ctrl');
  if (c.alt) out.push(mac ? '⌥' : 'Alt');
  if (c.shift) out.push(mac ? '⇧' : 'Shift');
  const names: Record<string, string> = {
    escape: 'Esc',
    enter: 'Enter',
    arrowup: '↑',
    arrowdown: '↓',
    arrowleft: '←',
    arrowright: '→',
    ' ': 'Space',
  };
  out.push(
    names[c.key] ?? (c.key.length === 1 || /^f\d{1,2}$/.test(c.key) ? c.key.toUpperCase() : c.key),
  );
  return out;
}

/**
 * Keyboard shortcuts: pages register what they offer and unregister when destroyed; later
 * registrations win for the same keys (a page overrides the shell). One document listener; typing
 * in inputs is ignored unless a shortcut opts in. `?` (registered by the shell) opens the overlay
 * that lists `visible()`.
 *
 *   constructor() {
 *     inject(ShortcutsService).register([
 *       { keys: 'j', description: 'Next item', group: 'List', handler: () => this.move(1) },
 *     ], inject(DestroyRef));
 *   }
 */
@Injectable({ providedIn: 'root' })
export class ShortcutsService {
  private readonly doc = inject(DOCUMENT);
  private readonly list = signal<Registered[]>([]);
  private seq = 0;

  /** The `?` overlay. */
  readonly overlayOpen = signal(false);

  /** Shortcuts shown in the overlay: effective (last registration per combination), grouped. */
  readonly visible = computed(() => {
    const seen = new Set<string>();
    const out: Registered[] = [];
    for (const s of [...this.list()].reverse()) {
      const k = JSON.stringify(s.combo);
      if (seen.has(k) || s.hidden || (s.when && !s.when())) continue;
      seen.add(k);
      out.push(s);
    }
    const groups = new Map<string, Registered[]>();
    for (const s of out.reverse()) {
      const g = s.group ?? 'Page';
      groups.set(g, [...(groups.get(g) ?? []), s]);
    }
    return [...groups.entries()].map(([group, items]) => ({ group, items }));
  });

  constructor() {
    const listener = (e: KeyboardEvent) => this.onKeydown(e);
    this.doc.addEventListener('keydown', listener);
    inject(DestroyRef).onDestroy(() => this.doc.removeEventListener('keydown', listener));
  }

  /** Registers shortcuts; returns the unregister function (also run on `destroyRef` destroy). */
  register(shortcuts: Shortcut | Shortcut[], destroyRef?: DestroyRef): () => void {
    const items = (Array.isArray(shortcuts) ? shortcuts : [shortcuts]).map((s) => ({
      ...s,
      id: ++this.seq,
      combo: parseKeys(s.keys),
    }));
    this.list.update((l) => [...l, ...items]);
    const ids = new Set(items.map((i) => i.id));
    const off = () => this.list.update((l) => l.filter((s) => !ids.has(s.id)));
    destroyRef?.onDestroy(off);
    return off;
  }

  /** Exposed for tests; normally driven by the document listener. */
  onKeydown(e: KeyboardEvent): void {
    if (e.defaultPrevented || e.isComposing) return;
    const typing = isTypingTarget(e.target);
    const list = this.list();
    for (let i = list.length - 1; i >= 0; i--) {
      const s = list[i];
      if (!matches(s.combo, e)) continue;
      if (typing && !s.allowInInputs) continue;
      if (s.when && !s.when(e)) continue;
      e.preventDefault();
      s.handler(e);
      return;
    }
  }
}

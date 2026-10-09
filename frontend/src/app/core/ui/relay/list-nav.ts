import { DestroyRef, inject, signal, type Signal } from '@angular/core';
import { ShortcutsService } from './shortcuts.service';

export interface ListNavigation {
  /** Index of the keyboard cursor, -1 when none. */
  cursor: Signal<number>;
  set(index: number): void;
  move(delta: number): void;
}

/**
 * Keyboard navigation for a list: `j` / `k` move the cursor, `Enter` opens the item under it.
 * Rows mark themselves with `[attr.data-nav-index]="i"` and `[class.active]="nav.cursor() === i"`;
 * the cursor row is scrolled into view. Call in an injection context.
 *
 *   readonly nav = listNavigation({ items: this.rows, open: (row) => this.openRow(row) });
 */
export function listNavigation<T>(opts: {
  items: Signal<readonly T[]>;
  open: (item: T, index: number) => void;
  group?: string;
  /** Extra condition (e.g. no panel open). */
  when?: () => boolean;
  /** Container to search for rows (defaults to the document). */
  root?: () => ParentNode | null | undefined;
}): ListNavigation {
  const shortcuts = inject(ShortcutsService);
  const destroyRef = inject(DestroyRef);
  const cursor = signal(-1);
  const group = opts.group ?? $localize`:@@core.listNav.group:List`;
  const enabled = () => (opts.when ? opts.when() : true) && opts.items().length > 0;

  const scroll = (i: number) =>
    queueMicrotask(() => {
      const root = opts.root?.() ?? document;
      const el = root.querySelector<HTMLElement>(`[data-nav-index="${i}"]`);
      el?.scrollIntoView?.({ block: 'nearest' });
    });
  const set = (i: number) => {
    const n = opts.items().length;
    if (!n) return cursor.set(-1);
    const next = Math.max(0, Math.min(n - 1, i));
    cursor.set(next);
    scroll(next);
  };
  const move = (d: number) =>
    set(cursor() < 0 ? (d > 0 ? 0 : opts.items().length - 1) : cursor() + d);

  shortcuts.register(
    [
      {
        keys: 'j',
        description: $localize`:@@core.listNav.next:Next item`,
        group,
        when: enabled,
        handler: () => move(1),
      },
      {
        keys: 'k',
        description: $localize`:@@core.listNav.previous:Previous item`,
        group,
        when: enabled,
        handler: () => move(-1),
      },
      {
        keys: 'Enter',
        description: $localize`:@@core.listNav.open:Open item`,
        group,
        // Only from the page itself or a row: a focused button keeps its own Enter.
        when: (e) =>
          enabled() &&
          cursor() >= 0 &&
          cursor() < opts.items().length &&
          (!e ||
            e.target === document.body ||
            !!(e.target as HTMLElement)?.hasAttribute?.('data-nav-index')),
        handler: () => opts.open(opts.items()[cursor()], cursor()),
      },
    ],
    destroyRef,
  );
  return { cursor: cursor.asReadonly(), set, move };
}

import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject } from '@angular/core';

/**
 * Fills accessibility gaps of NG-ZORRO 21 widgets app-wide (found by the axe checks of
 * `npm run e2e:smoke`), without touching every template:
 *
 * - `nz-select` / `nz-input-number` / `nz-date-picker`: the inner `<input>` gets the host's
 *   `aria-label`, or the text of its `<label for=...>` / form label, or the placeholder;
 * - `nz-switch`: the inner `<button class="ant-switch">` gets the host's `aria-label` (or the
 *   switch's text / the nearest label);
 * - `nz-segmented`: the invalid `aria-selected` on item labels is removed (each item is a native
 *   radio input, which carries the state) and the group takes the host's `aria-label`.
 *
 * Runs on DOM changes, batched per animation frame. Remove a rule once NG-ZORRO fixes it upstream.
 */
@Injectable({ providedIn: 'root' })
export class A11yPatchService {
  private readonly doc = inject(DOCUMENT);
  private scheduled = false;

  constructor() {
    const view = this.doc.defaultView;
    if (!view || typeof MutationObserver === 'undefined') return;
    const observer = new MutationObserver(() => this.schedule());
    observer.observe(this.doc.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['aria-selected', 'aria-label'],
    });
    inject(DestroyRef).onDestroy(() => observer.disconnect());
    this.schedule();
  }

  private schedule(): void {
    if (this.scheduled) return;
    this.scheduled = true;
    const run = () => {
      this.scheduled = false;
      this.patch();
    };
    const raf = this.doc.defaultView?.requestAnimationFrame;
    if (raf) raf(run);
    else setTimeout(run, 16);
  }

  /** Exposed for tests. */
  patch(root: ParentNode = this.doc): void {
    for (const host of Array.from(
      root.querySelectorAll<HTMLElement>(
        'nz-select, nz-input-number, nz-date-picker, nz-range-picker, nz-auto-complete',
      ),
    )) {
      const input = host.querySelector<HTMLInputElement>('input');
      if (!input || input.hasAttribute('aria-label') || input.hasAttribute('aria-labelledby'))
        continue;
      const name =
        this.nameOf(host) ||
        input.placeholder ||
        host.getAttribute('nzplaceholder') ||
        (host.classList.contains('ant-pagination-options-size-changer')
          ? $localize`:@@core.a11y.rowsPerPage:Rows per page`
          : null) ||
        host.querySelector('.ant-select-selection-item')?.textContent?.trim() ||
        null;
      if (name) input.setAttribute('aria-label', name);
    }
    for (const host of Array.from(root.querySelectorAll<HTMLElement>('nz-switch'))) {
      const button = host.querySelector<HTMLElement>('button.ant-switch');
      if (!button || button.hasAttribute('aria-label') || button.hasAttribute('aria-labelledby'))
        continue;
      const name = this.nameOf(host) || (host.textContent ?? '').trim();
      button.setAttribute('aria-label', name || 'Toggle');
    }
    for (const label of Array.from(
      root.querySelectorAll<HTMLElement>('.ant-segmented-item-label[aria-selected]'),
    )) {
      label.removeAttribute('aria-selected');
    }
    for (const seg of Array.from(root.querySelectorAll<HTMLElement>('nz-segmented[data-label]'))) {
      const wanted = seg.dataset.label!;
      if (seg.getAttribute('aria-label') !== wanted) seg.setAttribute('aria-label', wanted);
    }
  }

  private nameOf(host: HTMLElement): string | null {
    const own = host.getAttribute('aria-label');
    if (own) return own;
    const id = host.id;
    if (id) {
      const label = Array.from(this.doc.querySelectorAll<HTMLLabelElement>('label[for]')).find(
        (l) => l.htmlFor === id,
      );
      const text = label?.textContent?.trim();
      if (text) return text.replace(/\s*\*$/, '');
    }
    const formLabel = host.closest('nz-form-item')?.querySelector('nz-form-label label, label');
    const text = formLabel?.textContent?.trim();
    if (text) return text;
    const wrap = host.closest('label');
    return wrap?.textContent?.trim() || null;
  }
}

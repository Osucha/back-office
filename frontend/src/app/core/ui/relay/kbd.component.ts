import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { formatKeys } from './shortcuts.service';

/**
 * Key hint: `<bo-kbd keys="mod+k" />` renders "Ctrl K" (or "⌘ K" on macOS) as key caps.
 * `quiet` drops the caps' border for hints inside buttons.
 */
@Component({
  selector: 'bo-kbd',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.quiet]': 'quiet()', '[attr.aria-label]': 'aria()' },
  template: `@for (k of parts(); track $index) {
    <kbd aria-hidden="true">{{ k }}</kbd>
  }`,
  styles: [
    `
      :host {
        display: inline-flex;
        gap: 2px;
        align-items: center;
        vertical-align: middle;
      }
      kbd {
        font-family: var(--bo-font-mono);
        font-size: 10.5px;
        line-height: 16px;
        min-width: 18px;
        height: 18px;
        padding: 0 4px;
        text-align: center;
        border-radius: 4px;
        border: 1px solid var(--bo-line-strong);
        border-bottom-width: 2px;
        background: var(--bo-surface);
        color: var(--bo-ink-2);
        box-sizing: border-box;
      }
      :host(.quiet) kbd {
        border-color: transparent;
        background: color-mix(in srgb, currentColor 14%, transparent);
        color: inherit;
        border-bottom-width: 1px;
      }
    `,
  ],
})
export class KbdComponent {
  readonly keys = input.required<string>();
  readonly quiet = input(false);
  readonly parts = computed(() => formatKeys(this.keys()));
  readonly aria = computed(
    () => $localize`:@@core.kbd.aria:Shortcut ${this.parts().join(' ')}:keys:`,
  );
}

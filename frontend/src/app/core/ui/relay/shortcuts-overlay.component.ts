import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  viewChild,
} from '@angular/core';
import { KbdComponent } from './kbd.component';
import { ShortcutsService } from './shortcuts.service';

/** The `?` overlay: every shortcut active on this screen, grouped. Rendered once by the shell. */
@Component({
  selector: 'bo-shortcuts-overlay',
  imports: [KbdComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (shortcuts.overlayOpen()) {
      <div class="scrim" (click)="close()" aria-hidden="true"></div>
      <div
        #dialog
        class="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="bo-shortcuts-title"
        tabindex="-1"
        (keydown.escape)="close()"
      >
        <header>
          <h2 id="bo-shortcuts-title" i18n="@@core.shortcuts.title">Keyboard shortcuts</h2>
          <button
            type="button"
            class="close"
            (click)="close()"
            aria-label="Close shortcuts"
            i18n-aria-label="@@core.shortcuts.close"
          >
            <bo-kbd keys="Escape" />
          </button>
        </header>
        <div class="groups">
          @for (g of shortcuts.visible(); track g.group) {
            <section>
              <h3>{{ g.group }}</h3>
              <dl>
                @for (s of g.items; track s.keys) {
                  <div class="row">
                    <dt>{{ s.description }}</dt>
                    <dd><bo-kbd [keys]="s.keys" /></dd>
                  </div>
                }
              </dl>
            </section>
          }
        </div>
      </div>
    }
  `,
  styles: [
    `
      .scrim {
        position: fixed;
        inset: 0;
        background: color-mix(in srgb, var(--bo-scrim) 35%, transparent);
        z-index: 1100;
      }
      .dialog {
        position: fixed;
        top: 12vh;
        left: 50%;
        transform: translateX(-50%);
        width: min(720px, calc(100vw - 32px));
        max-height: 72vh;
        overflow: auto;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        box-shadow: var(--bo-shadow-overlay);
        z-index: 1101;
        padding: 16px 20px 20px;
        outline: none;
      }
      header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 8px;
      }
      h2 {
        font-size: 16px;
        margin: 0;
      }
      .close {
        all: unset;
        cursor: pointer;
        border-radius: 4px;
      }
      .close:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .groups {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
        gap: 8px 24px;
      }
      h3 {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-3);
        margin: 12px 0 4px;
      }
      dl {
        margin: 0;
      }
      .row {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 12px;
        padding: 5px 0;
        border-bottom: 1px solid var(--bo-line);
        font-size: 13px;
      }
      dd {
        margin: 0;
        flex: none;
      }
    `,
  ],
})
export class ShortcutsOverlayComponent {
  readonly shortcuts = inject(ShortcutsService);
  private readonly dialog = viewChild<ElementRef<HTMLElement>>('dialog');
  private returnFocus: HTMLElement | null = null;

  constructor() {
    effect(() => {
      const el = this.dialog()?.nativeElement;
      if (el) {
        this.returnFocus = document.activeElement as HTMLElement | null;
        el.focus();
      }
    });
  }

  close(): void {
    this.shortcuts.overlayOpen.set(false);
    this.returnFocus?.focus?.();
    this.returnFocus = null;
  }
}

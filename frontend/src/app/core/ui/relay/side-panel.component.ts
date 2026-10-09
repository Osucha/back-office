import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
  type Signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';

let nextId = 0;

/**
 * Side panel: a 480–720 px resizable drawer for details next to a list (the list stays usable).
 * `Esc` closes it, focus moves in on open and back on close; the width is remembered per
 * `storageKey`. Deep-link it with `panelParam()` (a query parameter).
 *
 *   <bo-side-panel [open]="!!selected()" [title]="selected()?.question ?? ''" (closed)="close()"
 *                  initialFocus="[data-focus=checklist]">
 *     <bo-baton boPanelHeader [baton]="b" />
 *     ...body...
 *     <div boPanelFooter>...decision buttons...</div>
 *   </bo-side-panel>
 */
@Component({
  selector: 'bo-side-panel',
  imports: [NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <aside
        #panel
        class="panel"
        role="dialog"
        aria-modal="false"
        [attr.aria-labelledby]="titleId"
        [style.width.px]="width()"
        [style.z-index]="zIndex()"
      >
        <div
          class="resize"
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize panel"
          i18n-aria-label="@@core.panel.resize"
          [attr.aria-valuenow]="width()"
          [attr.aria-valuemin]="minWidth()"
          [attr.aria-valuemax]="maxWidth()"
          tabindex="0"
          (pointerdown)="startResize($event)"
          (keydown.arrowleft)="nudge(24); $event.preventDefault()"
          (keydown.arrowright)="nudge(-24); $event.preventDefault()"
        ></div>
        <header class="head">
          <div class="head-main">
            <div class="head-extra"><ng-content select="[boPanelHeader]" /></div>
            <h2 class="title" [id]="titleId" tabindex="-1">{{ title() }}</h2>
            @if (subtitle()) {
              <div class="subtitle">{{ subtitle() }}</div>
            }
          </div>
          <button
            type="button"
            class="close"
            (click)="close()"
            aria-label="Close panel"
            i18n-aria-label="@@core.panel.close"
          >
            <nz-icon nzType="close" />
          </button>
        </header>
        <div
          class="body"
          [attr.tabindex]="bodyLabel() ? 0 : null"
          [attr.role]="bodyLabel() ? 'region' : null"
          [attr.aria-label]="bodyLabel()"
        >
          <ng-content />
        </div>
        <footer class="foot"><ng-content select="[boPanelFooter]" /></footer>
      </aside>
    }
  `,
  styles: [
    `
      .panel {
        position: fixed;
        top: var(--bo-topbar-h);
        right: 0;
        bottom: 0;
        max-width: calc(100vw - var(--bo-rail-w));
        display: flex;
        flex-direction: column;
        background: var(--bo-surface);
        border-left: 1px solid var(--bo-line);
        box-shadow: var(--bo-shadow-overlay);
        z-index: 900;
        animation: bo-panel-in var(--bo-dur-panel) var(--bo-ease);
      }
      @keyframes bo-panel-in {
        from {
          transform: translateX(24px);
          opacity: 0;
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .panel {
          animation: none;
        }
      }
      .resize {
        position: absolute;
        left: -4px;
        top: 0;
        bottom: 0;
        width: 8px;
        cursor: col-resize;
        z-index: 1;
      }
      .resize::after {
        content: '';
        position: absolute;
        left: 3px;
        top: 50%;
        width: 2px;
        height: 32px;
        margin-top: -16px;
        border-radius: 2px;
        background: var(--bo-line-strong);
        opacity: 0;
        transition: opacity var(--bo-dur-fast);
      }
      .resize:hover::after,
      .resize:focus-visible::after {
        opacity: 1;
      }
      .resize:focus-visible {
        outline: none;
      }
      .head {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        padding: 16px 20px 12px;
        border-bottom: 1px solid var(--bo-line);
      }
      .head-main {
        flex: 1;
        min-width: 0;
      }
      .head-extra:empty {
        display: none;
      }
      .head-extra {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
        margin-bottom: 6px;
      }
      .title {
        margin: 0;
        font-size: 16px;
        font-weight: 600;
        line-height: 1.35;
        outline: none;
        overflow-wrap: anywhere;
      }
      .subtitle {
        margin-top: 2px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .close {
        all: unset;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 28px;
        height: 28px;
        border-radius: var(--bo-radius-control);
        color: var(--bo-ink-2);
        flex: none;
      }
      .close:hover {
        background: var(--bo-surface-3);
        color: var(--bo-ink);
      }
      .close:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .body {
        flex: 1;
        min-height: 0;
        overflow: auto;
        padding: 16px 20px 24px;
      }
      .body:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .foot:empty {
        display: none;
      }
      .foot {
        border-top: 1px solid var(--bo-line);
        padding: 12px 20px;
        background: var(--bo-surface);
      }
    `,
  ],
})
export class SidePanelComponent {
  private readonly doc = inject(DOCUMENT);

  readonly open = input(false);
  readonly title = input('');
  readonly subtitle = input<string | null>(null);
  readonly minWidth = input(480);
  readonly maxWidth = input(720);
  readonly defaultWidth = input(560);
  readonly storageKey = input('backoffice.panelWidth');
  /** CSS selector inside the panel to focus on open (default: the title). */
  readonly initialFocus = input<string | null>(null);
  readonly closeOnEscape = input(true);
  /** Stacking level; the help panel sits above dialogs (it may be opened from one). */
  readonly zIndex = input<number | null>(null);
  /**
   * Accessible name of the scrolling body. When set, the body is a focusable region so keyboard
   * users can scroll it even when it holds no focusable content (read-only forms; axe
   * `scrollable-region-focusable`).
   */
  readonly bodyLabel = input<string | null>(null);
  readonly closed = output<void>();

  readonly titleId = `bo-panel-title-${++nextId}`;
  private readonly stored = signal<number | null>(null);
  readonly width = computed(() =>
    clamp(this.stored() ?? this.defaultWidth(), this.minWidth(), this.maxWidth()),
  );
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private returnFocus: HTMLElement | null = null;

  constructor() {
    effect(() => {
      const key = this.storageKey();
      untracked(() => this.stored.set(readWidth(key)));
    });
    effect(() => {
      const el = this.panel()?.nativeElement;
      if (!el) return;
      untracked(() => {
        this.returnFocus = this.doc.activeElement as HTMLElement | null;
        const selector = this.initialFocus();
        queueMicrotask(() => {
          const target =
            (selector && el.querySelector<HTMLElement>(selector)) ||
            el.querySelector<HTMLElement>('.title');
          target?.focus({ preventScroll: true });
        });
      });
    });
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || !this.open() || !this.closeOnEscape() || e.defaultPrevented) return;
      // An open dropdown/select/picker handles its own Escape.
      if (
        this.doc.querySelector(
          '.cdk-overlay-container .ant-select-dropdown, .cdk-overlay-container .ant-dropdown:not(.ant-dropdown-hidden), .ant-modal-wrap',
        )
      )
        return;
      e.preventDefault();
      this.close();
    };
    this.doc.addEventListener('keydown', onKey);
    inject(DestroyRef).onDestroy(() => this.doc.removeEventListener('keydown', onKey));
  }

  close(): void {
    this.closed.emit();
    const back = this.returnFocus;
    this.returnFocus = null;
    if (back && this.doc.contains(back)) queueMicrotask(() => back.focus({ preventScroll: true }));
  }

  nudge(delta: number): void {
    this.setWidth(this.width() + delta);
  }

  startResize(e: PointerEvent): void {
    e.preventDefault();
    const startX = e.clientX;
    const startW = this.width();
    const move = (ev: PointerEvent) => this.setWidth(startW + (startX - ev.clientX));
    const up = () => {
      this.doc.removeEventListener('pointermove', move);
      this.doc.removeEventListener('pointerup', up);
    };
    this.doc.addEventListener('pointermove', move);
    this.doc.addEventListener('pointerup', up);
  }

  private setWidth(w: number): void {
    const next = clamp(Math.round(w), this.minWidth(), this.maxWidth());
    this.stored.set(next);
    try {
      localStorage.setItem(this.storageKey(), String(next));
    } catch {
      // storage unavailable
    }
  }
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

function readWidth(key: string): number | null {
  try {
    const v = Number(localStorage.getItem(key));
    return Number.isFinite(v) && v > 0 ? v : null;
  } catch {
    return null;
  }
}

export interface PanelParam {
  /** The id in the URL, or null. */
  value: Signal<string | null>;
  open(id: string): void;
  close(): void;
}

/**
 * Deep-linkable side panel state in a query parameter (`?panel=<id>`), so a panel survives reload
 * and can be shared. Call in an injection context.
 *
 *   readonly panel = panelParam('run');
 *   <bo-side-panel [open]="!!panel.value()" (closed)="panel.close()">
 */
export function panelParam(name = 'panel'): PanelParam {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const params = toSignal(route.queryParamMap, { initialValue: route.snapshot.queryParamMap });
  const set = (v: string | null) =>
    void router.navigate([], {
      relativeTo: route,
      queryParams: { [name]: v },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  return {
    value: computed(() => params().get(name)),
    open: (id) => set(id),
    close: () => set(null),
  };
}

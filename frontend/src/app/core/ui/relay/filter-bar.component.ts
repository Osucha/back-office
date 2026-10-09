import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { BATON_HOLDERS, HOLDER_NAME, type BatonHolder } from './baton';
import { GlyphComponent } from './glyph.component';

export interface FilterChip {
  id: string;
  label: string;
}

export interface FilterPreset {
  label: string;
  /** Opaque value handed back through `presetSelected`. */
  value: unknown;
  active?: boolean;
}

/**
 * Filter bar: search field + holder toggles + projected controls (selects) + removable chips for
 * the active filters + presets ("saved views"). Stateless: bind it to `urlFilters()`.
 *
 *   <bo-filter-bar placeholder="Search by issue key or run id" [query]="f.values().q ?? ''"
 *                  (queryChange)="f.set({ q: $event })"
 *                  [holders]="holderOptions" [selectedHolders]="f.values().holder"
 *                  (selectedHoldersChange)="f.set({ holder: $event })"
 *                  [chips]="chips()" (chipRemoved)="remove($event)" (cleared)="f.clear()">
 *     <nz-select ...project filter... />
 *   </bo-filter-bar>
 *
 * Pages usually register `f` to call `focus()` (`/` belongs to the command palette).
 */
@Component({
  selector: 'bo-filter-bar',
  imports: [NzIconModule, GlyphComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bar" role="search" [attr.aria-label]="label()">
      <label class="search">
        <nz-icon nzType="search" class="search-icon" aria-hidden="true" />
        <span class="sr-only">{{ placeholder() }}</span>
        <input
          #search
          type="search"
          [placeholder]="placeholder()"
          [value]="draft()"
          (input)="onInput($any($event.target).value)"
          (keydown.escape)="onInput(''); search.blur()"
        />
      </label>
      @if (holders().length) {
        <div
          class="holders"
          role="group"
          aria-label="Filter by holder"
          i18n-aria-label="@@core.filter.byHolder"
        >
          @for (h of holders(); track h) {
            <button
              type="button"
              class="holder"
              [class.on]="selectedHolders().includes(h)"
              [attr.aria-pressed]="selectedHolders().includes(h)"
              [attr.data-holder]="h"
              (click)="toggle(h)"
            >
              <bo-glyph [holder]="h" [size]="11" />
              {{ holderName(h) }}
            </button>
          }
        </div>
      }
      <div class="controls"><ng-content /></div>
      @if (presets().length) {
        <div class="presets" role="group" aria-label="Views" i18n-aria-label="@@core.filter.views">
          @for (p of presets(); track p.label) {
            <button
              type="button"
              class="preset"
              [class.on]="p.active"
              [attr.aria-pressed]="!!p.active"
              (click)="presetSelected.emit(p.value)"
            >
              {{ p.label }}
            </button>
          }
        </div>
      }
    </div>
    @if (chips().length) {
      <div class="chips" aria-label="Active filters" i18n-aria-label="@@core.filter.active">
        @for (c of chips(); track c.id) {
          <span class="chip">
            {{ c.label }}
            <button
              type="button"
              (click)="chipRemoved.emit(c.id)"
              [attr.aria-label]="removeLabel(c.label)"
            >
              <nz-icon nzType="close" />
            </button>
          </span>
        }
        <button type="button" class="clear" (click)="cleared.emit()" i18n="@@core.filter.clear">
          Clear filters
        </button>
      </div>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        margin-bottom: var(--bo-space-3);
      }
      .bar {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
      }
      .search {
        position: relative;
        display: flex;
        align-items: center;
        flex: 1 1 240px;
        max-width: 360px;
      }
      .search-icon {
        position: absolute;
        left: 10px;
        color: var(--bo-ink-3);
        font-size: 13px;
      }
      input {
        width: 100%;
        height: var(--bo-control-h);
        padding: 0 10px 0 30px;
        border: 1px solid var(--bo-line-strong);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface);
        color: var(--bo-ink);
        font: inherit;
        font-size: 13px;
        outline: none;
        transition: border-color var(--bo-dur-fast) var(--bo-ease);
      }
      input::placeholder {
        color: var(--bo-ink-3);
      }
      input:focus {
        border-color: var(--bo-accent);
        box-shadow: 0 0 0 2px var(--bo-accent-soft);
      }
      .holders,
      .presets {
        display: flex;
        gap: 4px;
        flex-wrap: wrap;
      }
      .holder,
      .preset {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        height: 28px;
        padding: 0 10px;
        border-radius: 999px;
        border: 1px solid var(--bo-line);
        background: var(--bo-surface);
        color: var(--bo-ink-2);
        font: inherit;
        font-size: 12px;
        cursor: pointer;
        transition:
          background var(--bo-dur-fast) var(--bo-ease),
          border-color var(--bo-dur-fast) var(--bo-ease);
      }
      .holder:hover,
      .preset:hover {
        border-color: var(--bo-line-strong);
        color: var(--bo-ink);
      }
      .holder.on,
      .preset.on {
        background: var(--bo-ink);
        border-color: var(--bo-ink);
        color: var(--bo-on-ink);
      }
      .holder:focus-visible,
      .preset:focus-visible,
      .chip button:focus-visible,
      .clear:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .controls {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
      }
      .controls:empty {
        display: none;
      }
      .presets {
        margin-left: auto;
      }
      .chips {
        display: flex;
        gap: 6px;
        align-items: center;
        flex-wrap: wrap;
        margin-top: 8px;
      }
      .chip {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        height: 24px;
        padding: 0 4px 0 10px;
        border-radius: 999px;
        background: var(--bo-accent-soft);
        color: var(--bo-accent);
        font-size: 12px;
        font-weight: 500;
      }
      .chip button {
        all: unset;
        cursor: pointer;
        display: inline-flex;
        width: 18px;
        height: 18px;
        align-items: center;
        justify-content: center;
        border-radius: 50%;
        font-size: 10px;
      }
      .chip button:hover {
        background: color-mix(in srgb, var(--bo-accent) 18%, transparent);
      }
      .clear {
        all: unset;
        cursor: pointer;
        font-size: 12px;
        color: var(--bo-ink-3);
        padding: 0 4px;
      }
      .clear:hover {
        color: var(--bo-ink);
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
      }
    `,
  ],
})
export class FilterBarComponent {
  removeLabel(label: string): string {
    return $localize`:@@core.filter.remove:Remove filter ${label}:filter:`;
  }

  readonly placeholder = input($localize`:@@core.filter.search:Search`);
  readonly label = input($localize`:@@core.filter.label:Filters`);
  readonly query = input('');
  /** Debounce for `queryChange` (ms). */
  readonly debounce = input(200);
  readonly holders = input<readonly BatonHolder[]>([]);
  readonly selectedHolders = input<readonly string[]>([]);
  readonly chips = input<FilterChip[]>([]);
  readonly presets = input<FilterPreset[]>([]);

  readonly queryChange = output<string>();
  readonly selectedHoldersChange = output<string[]>();
  readonly chipRemoved = output<string>();
  readonly presetSelected = output<unknown>();
  readonly cleared = output<void>();

  readonly allHolders = BATON_HOLDERS;
  readonly draft = signal('');
  private readonly search = viewChild<ElementRef<HTMLInputElement>>('search');
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => this.draft.set(this.query()));
  }

  /** Focuses the search field (for the page's `/` shortcut). */
  focus(): void {
    this.search()?.nativeElement.focus();
    this.search()?.nativeElement.select();
  }

  onInput(value: string): void {
    this.draft.set(value);
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.queryChange.emit(value.trim()), this.debounce());
  }

  toggle(h: BatonHolder): void {
    const cur = this.selectedHolders();
    this.selectedHoldersChange.emit(cur.includes(h) ? cur.filter((x) => x !== h) : [...cur, h]);
  }

  holderName(h: BatonHolder): string {
    return HOLDER_NAME[h];
  }
}

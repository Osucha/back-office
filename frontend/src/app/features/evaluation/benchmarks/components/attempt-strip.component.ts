import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { BenchmarkStatus, BenchmarkSummary } from '../../../../core/api/types';
import { GlyphComponent } from '../../../../core/ui/relay/glyph.component';
import { progressSegments, progressText } from '../data/benchmark-view';

/**
 * Attempt progress of a benchmark as a strip of baton-coloured segments (succeeded, failed, to go)
 * with the count in words; `legend` adds glyph + word per segment. State is never colour alone:
 * the text and the accessible name carry it.
 */
@Component({
  selector: 'app-attempt-strip',
  imports: [GlyphComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.full]': 'legend()' },
  template: `
    <div class="bar" role="img" [attr.aria-label]="aria()">
      @for (s of segments(); track s.holder) {
        <span
          class="seg"
          [attr.data-holder]="s.holder"
          [class.live]="s.holder === 'agent'"
          [style.width.%]="s.percent"
        ></span>
      }
    </div>
    @if (legend()) {
      <div class="legend" aria-hidden="true">
        @for (s of segments(); track s.holder) {
          <span class="item"
            ><bo-glyph [holder]="s.holder" [live]="s.holder === 'agent'" [size]="11" />
            {{ s.count }} {{ s.label }}</span
          >
        }
        <span class="total">{{ text() }}</span>
      </div>
    } @else {
      <span class="text">{{ text() }}</span>
    }
  `,
  styles: [
    `
      :host {
        display: flex;
        align-items: center;
        gap: 10px;
        min-width: 0;
      }
      :host(.full) {
        flex-direction: column;
        align-items: stretch;
        gap: 8px;
      }
      .bar {
        flex: 1;
        display: flex;
        gap: 2px;
        height: 6px;
        min-width: 60px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        overflow: hidden;
      }
      :host(.full) .bar {
        flex: none;
        height: 8px;
      }
      .seg {
        height: 100%;
        min-width: 3px;
      }
      .seg[data-holder='done'] {
        background: var(--bo-done);
      }
      .seg[data-holder='blocked'] {
        background: var(--bo-blocked);
      }
      .seg[data-holder='agent'] {
        background: var(--bo-agent-soft);
        box-shadow: inset 0 0 0 1px var(--bo-agent);
      }
      .seg[data-holder='idle'] {
        background: var(--bo-idle-soft);
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
      }
      .text,
      .total {
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
        color: var(--bo-ink-2);
        white-space: nowrap;
        font-variant-numeric: tabular-nums;
      }
      .legend {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 16px;
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .item {
        display: inline-flex;
        align-items: center;
        gap: 5px;
      }
      .total {
        margin-left: auto;
      }
    `,
  ],
})
export class AttemptStripComponent {
  readonly progress = input<BenchmarkSummary['progress'] | null | undefined>(null);
  readonly status = input<BenchmarkStatus | null | undefined>(null);
  readonly legend = input(false);

  readonly segments = computed(() => progressSegments(this.progress(), this.status()));
  readonly text = computed(() => progressText(this.progress(), this.status()));
  readonly aria = computed(() => {
    const parts = this.segments().map((s) => `${s.count} ${s.label}`);
    const list = parts.length ? parts.join(', ') : this.text();
    return $localize`:@@evaluation.strip.aria:Attempts: ${list}:list:`;
  });
}

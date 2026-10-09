import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { HOLDER_MEANING, type Baton, type BatonHolder } from './baton';
import { GlyphComponent } from './glyph.component';

/**
 * Baton chip: glyph + word (+ detail). The single status component of the app.
 *
 *   <bo-baton [baton]="batonOf.run(run)" />
 *   <bo-baton holder="you" label="your turn" detail="spec gate" />
 *   <bo-baton [baton]="b" appearance="plain" size="sm" />   (glyph + word, no pill; dense tables)
 */
@Component({
  selector: 'bo-baton',
  imports: [GlyphComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.data-holder]': 'resolved().holder',
    '[attr.data-appearance]': 'appearance()',
    '[attr.data-size]': 'size()',
    '[attr.title]': 'title()',
  },
  template: `
    <bo-glyph
      [holder]="resolved().holder"
      [live]="!!resolved().live"
      [size]="size() === 'sm' ? 11 : 12"
    />
    <span class="label">{{ resolved().label }}</span>
    @if (resolved().detail) {
      <span class="sep" aria-hidden="true">·</span>
      <span class="detail">{{ resolved().detail }}</span>
    }
  `,
  styles: [
    `
      :host {
        --tint: var(--bo-idle-soft);
        --ink: var(--bo-idle-ink);
        display: inline-flex;
        align-items: center;
        gap: 6px;
        max-width: 100%;
        height: 22px;
        padding: 0 9px 0 7px;
        border-radius: var(--bo-radius-chip);
        background: var(--tint);
        color: var(--ink);
        font-size: 12px;
        font-weight: 500;
        line-height: 1;
        white-space: nowrap;
        vertical-align: middle;
        font-variant-numeric: tabular-nums;
      }
      :host([data-size='sm']) {
        height: 20px;
        gap: 5px;
        padding: 0 8px 0 6px;
        font-size: 11.5px;
      }
      :host([data-holder='agent']) {
        --tint: var(--bo-agent-soft);
        --ink: var(--bo-agent-ink);
      }
      :host([data-holder='you']) {
        --tint: var(--bo-you-soft);
        --ink: var(--bo-you-ink);
        box-shadow: inset 0 0 0 1px var(--bo-you);
        font-weight: 600;
      }
      :host([data-holder='ci']) {
        --tint: var(--bo-ci-soft);
        --ink: var(--bo-ci-ink);
      }
      :host([data-holder='done']) {
        --tint: var(--bo-done-soft);
        --ink: var(--bo-done-ink);
      }
      :host([data-holder='blocked']) {
        --tint: var(--bo-blocked-soft);
        --ink: var(--bo-blocked-ink);
      }
      :host([data-appearance='plain']) {
        background: none;
        box-shadow: none;
        padding: 0;
        height: auto;
      }
      .label,
      .detail {
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .detail {
        font-weight: 400;
        font-family: var(--bo-font-mono);
        font-size: 11px;
      }
      .sep {
        opacity: 0.6;
      }
    `,
  ],
})
export class BatonComponent {
  /** A mapped baton (`batonOf.*`); or give `holder` / `label` / `detail` individually. */
  readonly baton = input<Baton | null>(null);
  readonly holder = input<BatonHolder>('idle');
  readonly label = input<string | null>(null);
  readonly detail = input<string | null>(null);
  readonly live = input(false);
  readonly appearance = input<'chip' | 'plain'>('chip');
  readonly size = input<'sm' | 'md'>('md');

  readonly resolved = computed<Baton>(() => {
    const b = this.baton();
    if (b) return b;
    return {
      holder: this.holder(),
      label: this.label() ?? this.holder(),
      detail: this.detail(),
      live: this.live(),
    };
  });
  readonly title = computed(() => HOLDER_MEANING[this.resolved().holder]);
}

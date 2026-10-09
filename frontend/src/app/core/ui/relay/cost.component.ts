import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import type { Money, TokenUsage } from '../../api/types';
import { formatCost, formatUsage } from './format';

/**
 * Cost tick: mono `$0.042`, tokens on hover. Appears next to every agent result.
 *
 *   <bo-cost [value]="node.cost" [usage]="node.usage" />
 */
@Component({
  selector: 'bo-cost',
  imports: [NzTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span
    class="tick"
    [class.zero]="isZero()"
    nz-tooltip
    [nzTooltipTitle]="tip() || null"
    [attr.aria-label]="aria()"
    [attr.tabindex]="tip() ? 0 : null"
    >{{ text() }}</span
  >`,
  styles: [
    `
      :host {
        display: inline-flex;
      }
      .tick {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        font-variant-numeric: tabular-nums;
        color: var(--bo-ink-2);
        white-space: nowrap;
      }
      .tick.zero {
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class CostComponent {
  readonly value = input<Money | number | null | undefined>(null);
  readonly usage = input<TokenUsage | null | undefined>(null);

  readonly text = computed(() => formatCost(this.value()));
  readonly tip = computed(() => formatUsage(this.usage()));
  readonly isZero = computed(() => {
    const v = this.value();
    const amount = typeof v === 'number' ? v : v?.amount;
    return !amount;
  });
  readonly aria = computed(() => {
    const t = this.tip();
    return t
      ? $localize`:@@core.cost.ariaUsage:Cost ${this.text()}:cost:, ${t}:usage:`
      : $localize`:@@core.cost.aria:Cost ${this.text()}:cost:`;
  });
}

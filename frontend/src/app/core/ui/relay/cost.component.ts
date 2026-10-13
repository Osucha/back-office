import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import type { Money, TokenUsage } from '../../api/types';
import { formatCost, formatUsage } from './format';

/**
 * Cost tick: mono `$0.042`, tokens on hover. Appears next to every agent result. With [apiValue] (ADR 0042) that
 * differs from the spent cost, e.g. a step a subscription paid for, it adds `· API $0.047`: the same work at API prices.
 *
 *   <bo-cost [value]="node.cost" [apiValue]="node.apiCost" [usage]="node.usage" />
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
    >
    @if (apiText(); as api) {
      <span
        class="tick api"
        nz-tooltip
        [nzTooltipTitle]="apiTip"
        tabindex="0"
        [attr.aria-label]="apiAria()"
        >&nbsp;· API {{ api }}</span
      >
    }`,
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
      .tick.api {
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class CostComponent {
  readonly value = input<Money | number | null | undefined>(null);
  readonly usage = input<TokenUsage | null | undefined>(null);
  /** ADR 0042: the cost at API prices; shown only when it differs from [value]. */
  readonly apiValue = input<Money | number | null | undefined>(null);

  readonly text = computed(() => formatCost(this.value()));
  readonly apiText = computed(() => {
    const api = amountOf(this.apiValue());
    return api !== null && api !== (amountOf(this.value()) ?? 0) ? formatCost(api) : null;
  });
  readonly apiTip = $localize`:@@core.cost.apiTip:The same work at API list prices. It differs from the cost when a subscription paid for steps (Claude Code with your own login).`;
  readonly apiAria = computed(
    () => $localize`:@@core.cost.apiAria:At API prices ${this.apiText()}:cost:`,
  );
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

function amountOf(v: Money | number | null | undefined): number | null {
  if (v === null || v === undefined) return null;
  return typeof v === 'number' ? v : (v.amount ?? null);
}

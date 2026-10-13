import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ActorRef, Money, TokenUsage } from '../../api/types';
import { actorName } from '../actor.component';
import { formatDuration } from '../pipes/duration.pipe';
import { timeAgo } from '../pipes/time-ago.pipe';
import { CostComponent } from './cost.component';
import { absoluteTime } from './format';

/**
 * Provenance line: who produced a result and what it cost.
 *
 *   <bo-provenance who="Implement" detail="OpenCode · claude-opus-5" [durationMs]="252000" [cost]="c" />
 *     -> by Implement (OpenCode · claude-opus-5) · 4m 12s · $0.31
 *   <bo-provenance verb="approved by" [actor]="decision.decidedBy" [at]="decision.decidedAt" />
 *     -> approved by Anna · 2h ago        (absolute time on hover)
 */
@Component({
  selector: 'bo-provenance',
  imports: [CostComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (name()) {
      <span class="part">
        {{ verb() }} <strong>{{ name() }}</strong>
        @if (detail()) {
          <span class="detail"> ({{ detail() }})</span>
        }
      </span>
    }
    @if (at()) {
      <span class="part" [attr.title]="absolute()">
        <time [attr.datetime]="at()">{{ relative() }}</time>
      </span>
    }
    @if (durationMs() !== null && durationMs() !== undefined) {
      <span class="part num">{{ duration() }}</span>
    }
    @if (cost()) {
      <span class="part"
        ><bo-cost [value]="cost()" [apiValue]="apiCost()" [usage]="usage()"
      /></span>
    }
    <ng-content />
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        flex-wrap: wrap;
        align-items: baseline;
        column-gap: 0;
        row-gap: 2px;
        font-size: 12px;
        color: var(--bo-ink-3);
        min-width: 0;
      }
      .part + .part::before {
        content: '·';
        margin: 0 6px;
        color: inherit;
      }
      strong {
        color: var(--bo-ink-2);
        font-weight: 500;
      }
      .detail {
        color: inherit;
      }
    `,
  ],
})
export class ProvenanceComponent {
  readonly verb = input($localize`:@@core.provenance.by:by`);
  readonly who = input<string | null>(null);
  readonly actor = input<ActorRef | null | undefined>(null);
  readonly detail = input<string | null>(null);
  readonly at = input<string | null | undefined>(null);
  readonly durationMs = input<number | null | undefined>(null);
  readonly cost = input<Money | null | undefined>(null);
  /** ADR 0042: the cost at API prices, shown next to [cost] when it differs. */
  readonly apiCost = input<Money | null | undefined>(null);
  readonly usage = input<TokenUsage | null | undefined>(null);

  readonly name = computed(() => {
    const w = this.who();
    if (w) return w;
    const a = this.actor();
    return a ? actorName(a) : null;
  });
  readonly relative = computed(() => timeAgo(this.at()));
  readonly absolute = computed(() => absoluteTime(this.at()));
  readonly duration = computed(() => formatDuration(this.durationMs()));
}

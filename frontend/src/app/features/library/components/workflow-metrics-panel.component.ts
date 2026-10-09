import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import type { WorkflowInsights } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { humanize } from '../../../core/ui/status-tag.component';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { formatRate, formatWait } from '../../insights/public-api';
import {
  MIN_RUNS_FOR_TREND,
  finishedRuns,
  formatLoops,
  metricCards,
  nodeRows,
  versionRows,
} from '../data/workflow-metrics';

/**
 * Effectiveness and efficiency of a workflow (ADR 0037): headline cards, the version comparison with trends against
 * the previous version, the projects using it (library scope) and the steps (failures, time, cost, ports taken).
 */
@Component({
  selector: 'app-workflow-metrics-panel',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    NzIconModule,
    NzTableModule,
    NzTooltipModule,
    CostComponent,
    EmptyComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (insights(); as i) {
      @if (!i.totals?.runs?.total) {
        <bo-empty
          tone="quiet"
          title="No runs in this period."
          i18n-title="@@library.metrics.empty"
          detail="Metrics appear once projects run this workflow. Try a longer period."
          i18n-detail="@@library.metrics.emptyDetail"
        />
      } @else {
        <section
          class="cards"
          aria-label="Headline metrics"
          i18n-aria-label="@@library.metrics.headlines"
        >
          @for (c of cards(); track c.id) {
            <article class="card metric" [attr.title]="c.hint">
              <h3 class="m-label">{{ c.label }}</h3>
              <div class="m-value">{{ c.value }}</div>
              <div class="m-sub">{{ c.sub }}</div>
            </article>
          }
        </section>
        <p class="note">
          {{ runsText() }}
          @if (i.totals?.runs?.modified) {
            <span class="chip outline" [attr.title]="text.modifiedHint">{{ modifiedText() }}</span>
          }
        </p>

        <section class="card" aria-labelledby="h-versions">
          <h3 id="h-versions" class="c-title" i18n="@@library.metrics.byVersion">By version</h3>
          <nz-table [nzData]="versions()" [nzShowPagination]="false" nzSize="small">
            <thead>
              <tr>
                <th nzWidth="90px" i18n="@@library.metrics.col.version">Version</th>
                <th nzAlign="right" i18n="@@library.metrics.col.runs">Runs</th>
                <th i18n="@@library.metrics.col.success">Success</th>
                <th i18n="@@library.metrics.col.firstPass">First pass</th>
                <th i18n="@@library.metrics.col.rework">Rework / run</th>
                <th i18n="@@library.metrics.col.duration">Median time</th>
                <th i18n="@@library.metrics.col.active">Active time</th>
                <th nzAlign="right" i18n="@@library.metrics.col.costPerSuccess">Cost / success</th>
              </tr>
            </thead>
            <tbody>
              @for (v of versions(); track v.version) {
                <tr>
                  <td class="mono-cell">
                    v{{ v.version }}
                    @if (v.fewRuns) {
                      <span class="quiet" [attr.title]="fewRunsHint" aria-hidden="true">*</span>
                    }
                  </td>
                  <td nzAlign="right" class="mono-cell">{{ v.metrics.runs?.total ?? 0 }}</td>
                  <td>
                    <span class="mono">{{ rate(v.metrics.successRate) }}</span>
                    <ng-container
                      *ngTemplateOutlet="trend; context: { $implicit: v.successTrend }"
                    />
                  </td>
                  <td class="mono-cell">{{ rate(v.metrics.firstPassRate) }}</td>
                  <td class="mono-cell">{{ loops(v.metrics.reworkLoopsPerRun) }}</td>
                  <td>
                    <span class="mono">{{ wait(v.metrics.duration?.p50Seconds) }}</span>
                    <ng-container
                      *ngTemplateOutlet="trend; context: { $implicit: v.durationTrend }"
                    />
                  </td>
                  <td class="mono-cell">{{ wait(v.metrics.activeTime?.p50Seconds) }}</td>
                  <td nzAlign="right">
                    @if (v.metrics.runs?.successful) {
                      <bo-cost [value]="v.metrics.costPerSuccessfulRun" />
                    } @else {
                      <span class="quiet">-</span>
                    }
                    <ng-container *ngTemplateOutlet="trend; context: { $implicit: v.costTrend }" />
                  </td>
                </tr>
              }
            </tbody>
          </nz-table>
          @if (anyFewRuns()) {
            <p class="foot">* {{ fewRunsHint }}</p>
          }
        </section>

        @if (scope() === 'library' && (i.byProject?.length ?? 0) > 0) {
          <section class="card" aria-labelledby="h-projects">
            <h3 id="h-projects" class="c-title" i18n="@@library.metrics.byProject">By project</h3>
            <nz-table [nzData]="i.byProject ?? []" [nzShowPagination]="false" nzSize="small">
              <thead>
                <tr>
                  <th i18n="@@library.metrics.col.project">Project</th>
                  <th nzAlign="right" i18n="@@library.metrics.col.runs">Runs</th>
                  <th i18n="@@library.metrics.col.success">Success</th>
                  <th i18n="@@library.metrics.col.firstPass">First pass</th>
                  <th i18n="@@library.metrics.col.duration">Median time</th>
                  <th nzAlign="right" i18n="@@library.metrics.col.costPerSuccess">Cost / success</th>
                </tr>
              </thead>
              <tbody>
                @for (p of i.byProject ?? []; track p.projectId) {
                  <tr>
                    <td>
                      <a class="mono-cell" [routerLink]="['/projects', p.projectId]">{{
                        p.projectKey
                      }}</a>
                    </td>
                    <td nzAlign="right" class="mono-cell">{{ p.metrics?.runs?.total ?? 0 }}</td>
                    <td class="mono-cell">{{ rate(p.metrics?.successRate) }}</td>
                    <td class="mono-cell">{{ rate(p.metrics?.firstPassRate) }}</td>
                    <td class="mono-cell">{{ wait(p.metrics?.duration?.p50Seconds) }}</td>
                    <td nzAlign="right">
                      @if (p.metrics?.runs?.successful) {
                        <bo-cost [value]="p.metrics?.costPerSuccessfulRun" />
                      } @else {
                        <span class="quiet">-</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </nz-table>
          </section>
        }

        <section class="card" aria-labelledby="h-nodes">
          <h3 id="h-nodes" class="c-title" i18n="@@library.metrics.byNode">Steps</h3>
          <nz-table [nzData]="nodes()" [nzShowPagination]="false" nzSize="small">
            <thead>
              <tr>
                <th i18n="@@library.metrics.col.step">Step</th>
                <th nzAlign="right" i18n="@@library.metrics.col.executions">Executions</th>
                <th i18n="@@library.metrics.col.failures">Failures</th>
                <th i18n="@@library.metrics.col.stepTime">Median / p90</th>
                <th nzAlign="right" i18n="@@library.metrics.col.cost">Cost</th>
                <th i18n="@@library.metrics.col.ports">Ports taken</th>
              </tr>
            </thead>
            <tbody>
              @for (n of nodes(); track n.nodeId) {
                <tr [class.hot]="!!n.hotspot">
                  <td>
                    <div class="cell-main">
                      <div class="cell-line">
                        <span class="cell-title">{{ n.nodeName }}</span>
                        @if (n.hotspot === 'failures') {
                          <span class="chip warn" i18n="@@library.metrics.hot.failures"
                            >fails often</span
                          >
                        } @else if (n.hotspot === 'cost') {
                          <span class="chip warn" i18n="@@library.metrics.hot.cost"
                            >most of the cost</span
                          >
                        }
                      </div>
                      <span class="cell-sub mono"
                        >{{ kind(n.kind) }}{{ n.templateKey ? ' · ' + n.templateKey : '' }}</span
                      >
                    </div>
                  </td>
                  <td nzAlign="right" class="mono-cell">{{ n.executions ?? 0 }}</td>
                  <td class="mono-cell">{{ n.failed ?? 0 }} · {{ rate(n.failureRate) }}</td>
                  <td class="mono-cell">
                    {{ wait(n.duration?.p50Seconds) }} / {{ wait(n.duration?.p90Seconds) }}
                  </td>
                  <td nzAlign="right"><bo-cost [value]="n.cost" /></td>
                  <td class="small">{{ n.portsText || '-' }}</td>
                </tr>
              }
            </tbody>
          </nz-table>
        </section>
      }
    }

    <ng-template #trend let-t>
      @if (t === 'better') {
        <nz-icon
          class="trend up"
          nzType="arrow-up"
          [attr.aria-label]="text.better"
          [nz-tooltip]="text.better"
        />
      } @else if (t === 'worse') {
        <nz-icon
          class="trend down"
          nzType="arrow-down"
          [attr.aria-label]="text.worse"
          [nz-tooltip]="text.worse"
        />
      }
    </ng-template>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .cards {
        display: grid;
        grid-template-columns: repeat(5, minmax(0, 1fr));
        gap: 12px;
      }
      @media (max-width: 1200px) {
        .cards {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
      }
      .card {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 14px 18px;
        min-width: 0;
      }
      .metric {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .m-label {
        margin: 0;
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      .m-value {
        font-family: var(--bo-font-mono);
        font-size: 26px;
        font-weight: 600;
        letter-spacing: -0.02em;
        font-variant-numeric: tabular-nums;
      }
      .m-sub,
      .foot {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .foot {
        margin: 8px 0 0;
      }
      .note {
        margin: -4px 0 0;
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        align-items: center;
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .c-title {
        margin: 0 0 10px;
        font-size: 14px;
        font-weight: 600;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .small {
        font-size: 12px;
      }
      .trend {
        margin-left: 6px;
        font-size: 12px;
      }
      .trend.up {
        color: var(--bo-done);
      }
      .trend.down {
        color: var(--bo-blocked);
      }
      .chip.warn {
        border-color: var(--bo-you);
        color: var(--bo-you-ink);
        background: var(--bo-you-soft);
      }
    `,
  ],
})
export class WorkflowMetricsPanelComponent {
  readonly insights = input<WorkflowInsights | null>(null);
  /** `library`: across projects (with the project table); `workflow`: one project workflow. */
  readonly scope = input<'library' | 'workflow'>('library');

  readonly cards = computed(() => {
    const t = this.insights()?.totals;
    return t ? metricCards(t) : [];
  });
  readonly versions = computed(() => versionRows(this.insights()?.byVersion));
  readonly nodes = computed(() => nodeRows(this.insights()?.byNode));
  readonly anyFewRuns = computed(() => this.versions().some((v) => v.fewRuns));
  readonly runsText = computed(() => {
    const r = this.insights()?.totals?.runs;
    return icu(
      $localize`:@@library.metrics.runs:{count, plural, one {# run} other {# runs}} in this period: {finished} finished, {active} in flight.`,
      {
        count: r?.total ?? 0,
        finished: finishedRuns(this.insights()?.totals),
        active: r?.active ?? 0,
      },
    );
  });
  readonly modifiedText = computed(() =>
    icu(
      $localize`:@@library.metrics.modified:{count, plural, one {# run of a locally changed copy} other {# runs of locally changed copies}}`,
      { count: this.insights()?.totals?.runs?.modified ?? 0 },
    ),
  );

  readonly fewRunsHint = icu(
    $localize`:@@library.metrics.fewRuns:fewer than {count} finished runs: the comparison is not reliable yet`,
    { count: MIN_RUNS_FOR_TREND },
  );
  readonly text = {
    better: $localize`:@@library.metrics.better:Better than the previous version`,
    worse: $localize`:@@library.metrics.worse:Worse than the previous version`,
    modifiedHint: $localize`:@@library.metrics.modifiedHint:These runs used a project copy whose graph differs from its library version; they are included in the totals.`,
  };

  rate(v: number | null | undefined): string {
    return formatRate(v);
  }

  wait(v: number | null | undefined): string {
    return formatWait(v);
  }

  loops(v: number | null | undefined): string {
    return formatLoops(v);
  }

  kind(k: string | null | undefined): string {
    return k ? humanize(k) : '';
  }
}

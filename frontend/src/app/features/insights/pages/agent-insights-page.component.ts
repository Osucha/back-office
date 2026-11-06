import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { InsightsApi } from '../../../core/api/insights.service';
import type { AgentInsights } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { ProjectsStore } from '../../../core/state/projects.store';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { InsightsNavComponent } from '../components/insights-nav.component';
import {
  type AgentRow,
  acceptedShare,
  agentRows,
  hasAgentData,
  templateGroups,
} from '../data/agent-insights-view';
import { PERIODS, type PeriodId, formatRate, formatWait, periodRange } from '../data/insights-view';

/** Sortable columns of the agent tables; without a sort the cheapest accepted result comes first. */
const agentColumns = {
  agent: (r: AgentRow) => r.agentName,
  model: (r: AgentRow) => r.model,
  executions: (r: AgentRow) => r.executions,
  accepted: (r: AgentRow) => r.accepted,
  firstPass: (r: AgentRow) => r.firstPassRate,
  cost: (r: AgentRow) => r.cost?.amount,
  perAccepted: (r: AgentRow) => r.costPerAccepted?.amount,
  duration: (r: AgentRow) => r.duration?.p50Seconds,
};

/**
 * Agent insights (`GET /insights/agents`, ADR 0059): the task steps of the visible runs per agent and
 * model (executions, accepted results, first-pass rate, cost and the cost of an accepted result),
 * then the same per template so agents are compared on the same task. Project and period live in
 * the URL, as on the delivery page.
 */
@Component({
  selector: 'app-agent-insights-page',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    NzSelectModule,
    NzTableModule,
    NzTooltipModule,
    CostComponent,
    EmptyComponent,
    InsightsNavComponent,
    PageHeaderComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="insights"
        title="Agents: cost and effectiveness"
        i18n-title="@@insights.agents.title"
        subtitle="Which agent and model gets a task step accepted for the least money, compared on the same templates."
        i18n-subtitle="@@insights.agents.subtitle"
        [menu]="menu"
      >
        <app-insights-nav boHeaderBelow active="agents" />
      </bo-page-header>

      <div
        class="toolbar"
        role="group"
        aria-label="Insights scope"
        i18n-aria-label="@@insights.page.scope"
      >
        <nz-select
          style="width: 240px"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="All visible projects"
          i18n-nzPlaceHolder="@@insights.page.allProjects"
          aria-label="Project"
          i18n-aria-label="@@insights.page.project"
          [ngModel]="f.values().projectId"
          (ngModelChange)="f.set({ projectId: $event })"
        >
          @for (p of projects.projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' · ' + p.name" />
          }
        </nz-select>
        <div
          class="periods"
          role="group"
          aria-label="Period"
          i18n-aria-label="@@insights.page.period"
        >
          @for (p of periods; track p.id) {
            <button
              type="button"
              class="period"
              [class.on]="period() === p.id"
              [attr.aria-pressed]="period() === p.id"
              (click)="f.set({ period: p.id === '30d' ? null : p.id })"
            >
              {{ p.label }}
            </button>
          }
        </div>
      </div>

      @if (loading() && !data()) {
        <bo-skeleton
          variant="table"
          [rows]="5"
          [columns]="8"
          label="Loading agent insights"
          i18n-label="@@insights.agents.loading"
        />
      } @else if (!ready()) {
        <bo-empty
          [title]="failed() ? text.unavailable : text.noData"
          [detail]="failed() ? text.unavailableDetail : text.noDataDetail"
          [actionLabel]="failed() ? text.tryAgain : period() !== '90d' ? text.show90 : null"
          (action)="failed() ? reload() : f.set({ period: '90d' })"
          help="insights"
          guideLabel="What insights measure"
          i18n-guideLabel="@@insights.page.guide"
        />
      } @else if (data(); as d) {
        <section class="card" aria-labelledby="h-agents">
          <h2 id="h-agents" i18n="@@insights.agents.byAgent">Per agent and model</h2>
          @if (!agents().length) {
            <bo-empty
              tone="quiet"
              title="No task step with an agent record in this period."
              i18n-title="@@insights.agents.noAgents"
            />
          } @else {
            <ng-container
              *ngTemplateOutlet="agentTable; context: { $implicit: agentList(), sort: sort }"
            />
          }
        </section>

        @if (templates().length) {
          <section class="card" aria-labelledby="h-templates">
            <h2 id="h-templates" i18n="@@insights.agents.byTemplate">Per template</h2>
            <p class="hint" i18n="@@insights.agents.byTemplateHint">
              The same agents on the same task: compare within one template.
            </p>
            @for (t of templates(); track t.templateKey; let first = $first) {
              <details class="tpl" [open]="first">
                <summary>
                  <span class="tpl-title">{{ t.title }}</span>
                  @if (t.title !== t.templateKey) {
                    <span class="mono quiet">{{ t.templateKey }}</span>
                  }
                  <span class="quiet">{{ agentsText(t.rows.length) }}</span>
                  <span class="quiet">{{ executionsText(t.executions) }}</span>
                </summary>
                <ng-container
                  *ngTemplateOutlet="
                    agentTable;
                    context: { $implicit: tplRows(t.rows), sort: tplSort }
                  "
                />
              </details>
            }
          </section>
        }

        <p class="note" role="note">
          <ng-container i18n="@@insights.agents.definitions"
            >Accepted = a succeeded execution that is the last one of its step in a run that ended
            successfully. Cost per accepted result = the cost of every execution / accepted
            executions: failed attempts and rework are paid by the accepted ones. First pass =
            accepted at the first attempt.</ng-container
          >
          @if (d.unattributed) {
            {{ unattributedText(d.unattributed) }}
          }
        </p>
      }
    </div>

    <ng-template #agentTable let-rows let-sort="sort">
      <nz-table [nzData]="rows" [nzShowPagination]="false" nzSize="small">
        <thead>
          <tr>
            <th nzShowSort [boSort]="sort" boSortKey="agent" i18n="@@insights.agents.col.agent">
              Agent
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="model"
              nzWidth="170px"
              i18n="@@insights.agents.col.model"
            >
              Model
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="executions"
              nzWidth="100px"
              nzAlign="right"
              i18n="@@insights.agents.col.executions"
            >
              Executions
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="accepted"
              nzWidth="110px"
              nzAlign="right"
              i18n="@@insights.agents.col.accepted"
            >
              Accepted
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="firstPass"
              nzWidth="120px"
              nzAlign="right"
              nz-tooltip
              nzTooltipTitle="Accepted executions at the first attempt / accepted executions"
              i18n-nzTooltipTitle="@@insights.agents.col.firstPassTooltip"
              i18n="@@insights.agents.col.firstPass"
            >
              First-pass rate
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="cost"
              nzWidth="96px"
              nzAlign="right"
              i18n="@@insights.agents.col.cost"
            >
              Cost
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="perAccepted"
              nzWidth="150px"
              nzAlign="right"
              nz-tooltip
              nzTooltipTitle="Cost of every execution / accepted executions"
              i18n-nzTooltipTitle="@@insights.agents.col.costPerAcceptedTooltip"
              i18n="@@insights.agents.col.costPerAccepted"
            >
              Cost per accepted result
            </th>
            <th
              nzShowSort
              [boSort]="sort"
              boSortKey="duration"
              nzWidth="110px"
              nzAlign="right"
              i18n="@@insights.agents.col.duration"
            >
              Median time
            </th>
          </tr>
        </thead>
        <tbody>
          @for (r of rows; track r.key) {
            <tr>
              <td>
                <span class="agent">
                  <span class="cell-title">{{ r.agentName }}</span>
                  <span class="chip" [class.accent]="!!r.harness">{{ r.kind }}</span>
                </span>
              </td>
              <td class="ink-2">{{ r.model }}</td>
              <td nzAlign="right" class="mono-cell">{{ r.executions }}</td>
              <td nzAlign="right">
                <span class="mono-cell">{{ r.accepted }}</span>
                <span class="quiet share">{{ rate(share(r)) }}</span>
              </td>
              <td nzAlign="right" class="mono-cell">{{ rate(r.firstPassRate) }}</td>
              <td nzAlign="right"><bo-cost [value]="r.cost" [apiValue]="r.apiCost" /></td>
              <td nzAlign="right">
                @if (r.costPerAccepted) {
                  <strong><bo-cost [value]="r.costPerAccepted" /></strong>
                } @else {
                  <span
                    class="quiet"
                    [attr.title]="text.noAccepted"
                    [attr.aria-label]="text.noAccepted"
                    >-</span
                  >
                }
              </td>
              <td nzAlign="right" class="mono-cell">{{ wait(r.duration?.p50Seconds) }}</td>
            </tr>
          }
        </tbody>
      </nz-table>
    </ng-template>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
        align-items: center;
        margin-bottom: 16px;
      }
      .periods {
        display: inline-flex;
        gap: 4px;
        margin-left: auto;
      }
      .period {
        height: 28px;
        padding: 0 12px;
        border-radius: 999px;
        border: 1px solid var(--bo-line-strong);
        background: var(--bo-surface);
        color: var(--bo-ink-2);
        font-size: 12.5px;
        cursor: pointer;
      }
      .period.on {
        background: var(--bo-ink);
        border-color: var(--bo-ink);
        color: var(--bo-on-ink);
      }
      .period:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .card {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 14px 18px;
        margin-bottom: 16px;
        min-width: 0;
      }
      h2 {
        margin: 0 0 10px;
        font-size: 14px;
        font-weight: 600;
      }
      .hint {
        margin: -4px 0 10px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .agent {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
      }
      .share {
        margin-left: 6px;
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .tpl {
        border-top: 1px solid var(--bo-line);
        padding: 8px 0;
      }
      .tpl summary {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 4px 12px;
        cursor: pointer;
        padding: 4px 0;
        font-size: 13px;
      }
      .tpl summary:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .tpl-title {
        font-weight: 600;
      }
      .tpl[open] summary {
        margin-bottom: 8px;
      }
      .note {
        margin: 0;
        font-size: 12px;
        color: var(--bo-ink-3);
        max-width: 110ch;
      }
    `,
  ],
})
export class AgentInsightsPageComponent {
  private readonly api = inject(InsightsApi);
  readonly projects = inject(ProjectsStore);

  readonly f = urlFilters({ projectId: 'string', period: 'string' });
  readonly periods = PERIODS;
  readonly data = signal<AgentInsights | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);

  readonly period = computed<PeriodId>(() => {
    const p = this.f.values().period as PeriodId | null;
    return p && PERIODS.some((x) => x.id === p) ? p : '30d';
  });
  readonly ready = computed(() => hasAgentData(this.data()));
  readonly agents = computed(() => agentRows(this.data()?.byAgent));
  readonly templates = computed(() => templateGroups(this.data()));
  /** Column sorting (URL `?sort=` for the agent table, `?tplSort=` for the template tables). */
  readonly sort = tableSort<AgentRow>(agentColumns);
  readonly tplSort = tableSort<AgentRow>(agentColumns, { param: 'tplSort' });
  readonly agentList = computed(() => this.sort.apply(this.agents()));

  readonly rate = formatRate;
  readonly wait = formatWait;
  readonly share = acceptedShare;
  readonly reload = () => this.load();
  readonly menu = [
    {
      label: $localize`:@@insights.agents.reload:Reload agent insights`,
      icon: 'reload',
      run: this.reload,
    },
  ];
  readonly text = {
    unavailable: $localize`:@@insights.page.unavailable:Insights are not available.`,
    unavailableDetail: $localize`:@@insights.agents.unavailableDetail:The API did not answer ${'/insights/agents'}:path:.`,
    noData: $localize`:@@insights.agents.noData:No task steps in this period yet.`,
    noDataDetail: $localize`:@@insights.agents.noDataDetail:Agent insights fill in as runs execute task steps. Pick a longer period or another project.`,
    tryAgain: $localize`:@@insights.page.tryAgain:Try again`,
    show90: $localize`:@@insights.page.show90:Show 90 days`,
    noAccepted: $localize`:@@insights.agents.noAccepted:No accepted result yet`,
  };

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@insights.page.crumb:Insights`, link: '/insights' },
      { label: $localize`:@@insights.agents.crumb:Agents` },
    ]);
    this.projects.ensure();
    effect(() => {
      this.f.values(); // reload when the project filter or the period changes
      this.period();
      untracked(() => this.load());
    });
  }

  /** Template tables share one sort; without one they keep the cheapest accepted result first. */
  tplRows(rows: AgentRow[]): AgentRow[] {
    return this.tplSort.apply(rows);
  }

  agentsText(count: number): string {
    return icu(
      $localize`:@@insights.agents.agentCount:{count, plural, one {# agent} other {# agents}}`,
      { count },
    );
  }

  executionsText(count: number): string {
    return icu(
      $localize`:@@insights.agents.executionCount:{count, plural, one {# execution} other {# executions}}`,
      { count },
    );
  }

  unattributedText(count: number): string {
    return icu(
      $localize`:@@insights.agents.unattributed:{count, plural, one {# task step execution has} other {# task step executions have}} no agent record (they ran before agents were recorded) and are not counted.`,
      { count },
    );
  }

  load(): void {
    const range = periodRange(this.period());
    this.loading.set(true);
    this.api.agents({ projectId: this.f.values().projectId, ...range }).subscribe({
      next: (d) => {
        this.data.set(d);
        this.failed.set(false);
        this.loading.set(false);
      },
      error: () => {
        this.data.set(null);
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }
}

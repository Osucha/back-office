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
import { Router, RouterLink } from '@angular/router';
import { NgTemplateOutlet } from '@angular/common';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NgxEchartsDirective } from 'ngx-echarts';
import { InsightsApi } from '../../../core/api/insights.service';
import { fixed } from '../../../core/i18n/numbers';
import type { Bottleneck, DeliveryInsights } from '../../../core/api/types';
import { ProjectsStore } from '../../../core/state/projects.store';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { chartTokensSignal } from '../../../core/ui/relay/chart-tokens';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import {
  PERIODS,
  type PeriodId,
  decisionRows,
  formatRate,
  formatWait,
  hasData,
  headlines,
  periodRange,
  successRows,
  weeklyLabel,
  weeklyOption,
} from '../data/insights-view';

/**
 * Delivery insights (`GET /insights/delivery`): where the time goes between an issue and a merged
 * change. Human wait is the headline (amber: time people owe), then lead time, CI wait, success
 * and cost; the bottleneck gate; a weekly chart (lazy ECharts); agent success by template and
 * model; gate decisions. Project and period live in the URL.
 */
@Component({
  selector: 'app-insights-page',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    NzTableModule,
    NgxEchartsDirective,
    BatonComponent,
    GlyphComponent,
    CostComponent,
    EmptyComponent,
    PageHeaderComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="insights"
        title="Insights"
        i18n-title="@@insights.page.title"
        subtitle="Where the time goes between an issue and a merged change, and what the agents' work cost."
        i18n-subtitle="@@insights.page.subtitle"
        [menu]="menu"
      />

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
          variant="cards"
          [rows]="2"
          label="Loading insights"
          i18n-label="@@insights.page.loading"
        />
      } @else if (!ready()) {
        <bo-empty
          [title]="failed() ? text.unavailable : text.noData"
          [detail]="failed() ? text.unavailableDetail : text.noDataDetail"
          [actionLabel]="
            failed() ? text.tryAgain : period() !== '90d' ? text.show90 : text.openRuns
          "
          (action)="
            failed() ? reload() : period() !== '90d' ? f.set({ period: '90d' }) : openRuns()
          "
          help="insights"
          guideLabel="What insights measure"
          i18n-guideLabel="@@insights.page.guide"
        />
      } @else if (data(); as d) {
        <section
          class="cards"
          aria-label="Headline figures"
          i18n-aria-label="@@insights.page.headlines"
        >
          @for (h of cards(); track h.id) {
            <article class="card metric" [class.headline]="h.tone === 'you'" [attr.title]="h.hint">
              <h2 class="m-label">
                @if (h.tone === 'you') {
                  <bo-glyph holder="you" [size]="12" />
                }
                {{ h.label }}
              </h2>
              <div class="m-value">{{ h.value }}</div>
              <div class="m-sub">{{ h.sub }}</div>
            </article>
          }
        </section>

        @if (d.bottleneck; as b) {
          <section class="bottleneck" aria-labelledby="h-bottleneck">
            <div>
              <h2 id="h-bottleneck" i18n="@@insights.bottleneck.title">
                Bottleneck: {{ b.nodeName || b.nodeId }}
              </h2>
              <p i18n="@@insights.bottleneck.text">
                The gate with the longest wait in <strong>{{ b.workflowName }}</strong
                >: p90 <strong class="mono">{{ wait(b.p90WaitSeconds) }}</strong
                >. Fewer approvers, a checklist that is quicker to tick or an accountable role on
                call shorten it.
              </p>
            </div>
            @if (designerLink(b); as l) {
              <a
                nz-button
                [routerLink]="l"
                [queryParams]="{ node: b.nodeId }"
                i18n="@@insights.bottleneck.open"
                >Open the gate in the designer</a
              >
            }
          </section>
        }

        <section class="card chart-card" aria-labelledby="h-weekly">
          <header class="c-head">
            <h2 id="h-weekly" i18n="@@insights.weekly.title">Runs and human wait per week</h2>
            <span class="hint">{{ reworkText(d) }}</span>
          </header>
          @defer (on viewport) {
            <div
              echarts
              [options]="weekly()"
              class="chart"
              role="img"
              [attr.aria-label]="weeklyText()"
            ></div>
          } @placeholder {
            <div class="chart placeholder" aria-hidden="true"></div>
          }
        </section>

        <div class="tables">
          <section class="card" aria-labelledby="h-tpl">
            <h2 id="h-tpl" i18n="@@insights.tables.byTemplate">Agent success by template</h2>
            <ng-container
              *ngTemplateOutlet="
                successTable;
                context: { $implicit: templates(), col: text.template }
              "
            />
          </section>
          <section class="card" aria-labelledby="h-model">
            <h2 id="h-model" i18n="@@insights.tables.byModel">Agent success by model</h2>
            <ng-container
              *ngTemplateOutlet="successTable; context: { $implicit: models(), col: text.model }"
            />
          </section>
          <section class="card" aria-labelledby="h-gates">
            <h2 id="h-gates" i18n="@@insights.tables.gateDecisions">Gate decisions</h2>
            @if (!decisions().length) {
              <bo-empty
                tone="quiet"
                title="No gate decisions in this period."
                i18n-title="@@insights.tables.noDecisions"
              />
            } @else {
              <ul class="decisions">
                @for (g of decisions(); track g.option) {
                  <li>
                    <bo-baton
                      size="sm"
                      appearance="plain"
                      [holder]="g.holder"
                      [label]="decisionLabel(g.option)"
                    />
                    <span class="track" aria-hidden="true"
                      ><span [attr.data-tone]="g.holder" [style.width.%]="g.share * 100"></span
                    ></span>
                    <span class="mono">{{ g.count }}</span>
                    <span class="quiet">{{ rate(g.share) }}</span>
                  </li>
                }
              </ul>
            }
          </section>
        </div>
      }
    </div>

    <ng-template #successTable let-rows let-col="col">
      @if (!rows.length) {
        <bo-empty
          tone="quiet"
          title="No agent jobs in this period."
          i18n-title="@@insights.tables.noJobs"
        />
      } @else {
        <nz-table [nzData]="rows" [nzShowPagination]="false" nzSize="small">
          <thead>
            <tr>
              <th>{{ col }}</th>
              <th nzWidth="70px" nzAlign="right" i18n="@@insights.tables.jobs">Jobs</th>
              <th nzWidth="130px" i18n="@@insights.tables.success">Success</th>
              <th nzWidth="80px" nzAlign="right" i18n="@@insights.tables.cost">Cost</th>
            </tr>
          </thead>
          <tbody>
            @for (r of rows; track r.key) {
              <tr>
                <td class="mono-cell">{{ r.key }}</td>
                <td nzAlign="right" class="mono-cell">{{ r.jobs }}</td>
                <td>
                  <span class="rate">
                    <span class="track" aria-hidden="true"
                      ><span
                        [attr.data-tone]="
                          r.rate >= 0.8 ? 'done' : r.rate >= 0.5 ? 'you' : 'blocked'
                        "
                        [style.width.%]="r.rate * 100"
                      ></span
                    ></span>
                    <span class="mono">{{ rate(r.rate) }}</span>
                  </span>
                </td>
                <td nzAlign="right"><bo-cost [value]="r.cost" /></td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
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
      .cards {
        display: grid;
        grid-template-columns: 1.4fr repeat(4, minmax(0, 1fr));
        gap: 12px;
        margin-bottom: 16px;
      }
      @media (max-width: 1200px) {
        .cards {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
        .cards .headline {
          grid-column: 1 / -1;
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
      .metric.headline {
        border-color: var(--bo-you);
        background: var(--bo-you-soft);
      }
      .m-label {
        display: flex;
        align-items: center;
        gap: 8px;
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
      .headline .m-value {
        font-size: 36px;
        color: var(--bo-you-ink);
      }
      .m-sub {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .headline .m-sub {
        color: var(--bo-ink-2);
      }
      .bottleneck {
        display: flex;
        flex-wrap: wrap;
        justify-content: space-between;
        align-items: center;
        gap: 12px 24px;
        margin-bottom: 16px;
        padding: 12px 18px;
        border-radius: var(--bo-radius-card);
        border: 1px solid var(--bo-line-strong);
        border-left: 4px solid var(--bo-you);
        background: var(--bo-surface);
      }
      .bottleneck h2 {
        margin: 0 0 2px;
        font-size: 14px;
        font-weight: 600;
      }
      .bottleneck p {
        margin: 0;
        font-size: 13px;
        color: var(--bo-ink-2);
        max-width: 90ch;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .chart-card {
        margin-bottom: 16px;
      }
      .c-head {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        gap: 12px;
      }
      h2 {
        margin: 0 0 10px;
        font-size: 14px;
        font-weight: 600;
      }
      .hint {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .chart {
        height: 260px;
      }
      .chart.placeholder {
        background: var(--bo-surface-2);
        border-radius: var(--bo-radius-control);
      }
      .tables {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(440px, 1fr));
        gap: 16px;
      }
      @media (max-width: 1200px) {
        .tables {
          grid-template-columns: 1fr;
        }
      }
      .rate {
        display: inline-flex;
        align-items: center;
        gap: 8px;
      }
      .track {
        display: inline-block;
        width: 64px;
        height: 6px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        overflow: hidden;
      }
      .track > span {
        display: block;
        height: 100%;
        background: var(--bo-idle);
      }
      .track > span[data-tone='done'] {
        background: var(--bo-done);
      }
      .track > span[data-tone='you'] {
        background: var(--bo-you);
      }
      .track > span[data-tone='blocked'] {
        background: var(--bo-blocked);
      }
      .track > span[data-tone='agent'] {
        background: var(--bo-agent);
      }
      .decisions {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 8px;
      }
      .decisions li {
        display: grid;
        grid-template-columns: minmax(120px, 1fr) 80px 36px 40px;
        gap: 8px;
        align-items: center;
        font-size: 13px;
      }
      .decisions .track {
        width: 100%;
      }
    `,
  ],
})
export class InsightsPageComponent {
  private readonly api = inject(InsightsApi);
  readonly projects = inject(ProjectsStore);
  private readonly router = inject(Router);

  readonly f = urlFilters({ projectId: 'string', period: 'string' });
  readonly periods = PERIODS;
  readonly data = signal<DeliveryInsights | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  private readonly tokens = chartTokensSignal();

  readonly period = computed<PeriodId>(() => {
    const p = this.f.values().period as PeriodId | null;
    return p && PERIODS.some((x) => x.id === p) ? p : '30d';
  });
  readonly ready = computed(() => hasData(this.data()));
  readonly cards = computed(() => headlines(this.data()));
  readonly templates = computed(() => successRows(this.data()?.agentSuccessByTemplate));
  readonly models = computed(() => successRows(this.data()?.agentSuccessByModel));
  readonly decisions = computed(() => decisionRows(this.data()?.gateDecisions));
  readonly weekly = computed(() => weeklyOption(this.data()?.weekly, this.tokens()));
  readonly weeklyText = computed(() => weeklyLabel(this.data()?.weekly));

  wait = formatWait;
  rate = formatRate;
  readonly reload = () => this.load();
  readonly menu = [
    { label: $localize`:@@insights.page.reload:Reload insights`, icon: 'reload', run: this.reload },
  ];
  readonly text = {
    unavailable: $localize`:@@insights.page.unavailable:Insights are not available.`,
    noData: $localize`:@@insights.page.noData:No delivery data in this period yet.`,
    unavailableDetail: $localize`:@@insights.page.unavailableDetail:The API did not answer ${'/insights/delivery'}:path:.`,
    noDataDetail: $localize`:@@insights.page.noDataDetail:Insights fill in as runs pass gates and CI. Start a run, or pick a longer period.`,
    tryAgain: $localize`:@@insights.page.tryAgain:Try again`,
    show90: $localize`:@@insights.page.show90:Show 90 days`,
    openRuns: $localize`:@@insights.page.openRuns:Open runs`,
    template: $localize`:@@insights.tables.template:Template`,
    model: $localize`:@@insights.tables.model:Model`,
  };

  reworkText(d: DeliveryInsights): string {
    const v = d.reworkLoopsPerRun;
    if (v === null || v === undefined) return '';
    return $localize`:@@insights.weekly.rework:${fixed(v, 1)}:loops: rework loops per run`;
  }

  decisionLabel(option: string): string {
    const labels: Record<string, string> = {
      APPROVE: $localize`:@@insights.decision.approve:approve`,
      MERGE: $localize`:@@insights.decision.merge:merge`,
      REVISE: $localize`:@@insights.decision.revise:revise`,
      REJECT: $localize`:@@insights.decision.reject:reject`,
    };
    return labels[option.toUpperCase()] ?? option.toLowerCase();
  }

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@insights.page.crumb:Insights` }]);
    this.projects.ensure();
    effect(() => {
      this.f.values(); // reload when the project filter or the period changes
      this.period();
      untracked(() => this.load());
    });
  }

  /** The contract names the gate but not its workflow id yet; link when the backend adds it. */
  designerLink(b: Bottleneck): unknown[] | null {
    const id = (b as Bottleneck & { workflowId?: string }).workflowId;
    return id ? ['/workflows', id, 'edit'] : null;
  }

  openRuns(): void {
    void this.router.navigate(['/runs']);
  }

  load(): void {
    const range = periodRange(this.period());
    this.loading.set(true);
    this.api.delivery({ projectId: this.f.values().projectId, ...range }).subscribe({
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

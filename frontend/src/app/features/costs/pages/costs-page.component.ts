import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NgxEchartsDirective } from 'ngx-echarts';
import { catchError, of } from 'rxjs';
import { ProjectsApi } from '../../../core/api/projects.service';
import { icu } from '../../../core/i18n/icu';
import { fixed, formatNumber } from '../../../core/i18n/numbers';
import { BudgetSummaryComponent } from '../../budgets/public-api';
import type { Project, UsageEntry, UsageGroupBy, UsageSummary } from '../../../core/api/types';
import { UsageApi } from '../../../core/api/usage.service';
import { formatDuration } from '../../../core/ui/pipes/duration.pipe';
import { formatTokens } from '../../../core/ui/pipes/tokens.pipe';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { chartTokensSignal } from '../../../core/ui/relay/chart-tokens';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import {
  FilterBarComponent,
  type FilterChip,
  type FilterPreset,
} from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { absoluteTime, formatCost, formatUsage } from '../../../core/ui/relay/format';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { serverSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import {
  PERIODS,
  apiRange,
  breakdownRows,
  compactUsage,
  contextLabel,
  dailySeries,
  filterLedger,
  isoDay,
  ledgerLink,
  rangeFor,
  rangeLabel,
  trendOption,
} from '../data/cost-view';

const GROUPS: { id: UsageGroupBy; label: string; title: string }[] = [
  {
    id: 'MODEL',
    label: $localize`:@@costs.group.model:Model`,
    title: $localize`:@@costs.group.byModel:By model`,
  },
  {
    id: 'PROJECT',
    label: $localize`:@@costs.group.project:Project`,
    title: $localize`:@@costs.group.byProject:By project`,
  },
  {
    id: 'BENCHMARK',
    label: $localize`:@@costs.group.benchmark:Benchmark`,
    title: $localize`:@@costs.group.byBenchmark:By benchmark`,
  },
  {
    id: 'CONTEXT_KIND',
    label: $localize`:@@costs.group.kind:Kind of work`,
    title: $localize`:@@costs.group.byKind:By kind of work`,
  },
  {
    id: 'DAY',
    label: $localize`:@@costs.group.day:Day`,
    title: $localize`:@@costs.group.byDay:By day`,
  },
];
const VIRTUAL_FROM = 100;

/**
 * Costs: the total for the period (large mono figure), cost per day (chart, lazy), the breakdown
 * by model / project / benchmark / kind of work as a bar list, and the usage ledger. Period,
 * group-by, project and search live in the URL.
 */
@Component({
  selector: 'app-costs-page',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    RouterLink,
    NzDatePickerModule,
    NzSelectModule,
    NzTableModule,
    NgxEchartsDirective,
    BudgetSummaryComponent,
    CostComponent,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="costs"
        title="Costs"
        i18n-title="@@costs.page.title"
        subtitle="Every LLM call and runner job in the usage ledger: what it cost, for which work and with which model."
        i18n-subtitle="@@costs.page.subtitle"
        [menu]="menu"
      >
        @defer (on idle) {
          <app-budget-summary boHeaderMeta [projectId]="f.values().projectId ?? null" />
        }
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search the ledger: model, step, run or benchmark"
        i18n-placeholder="@@costs.page.search"
        label="Filter costs"
        i18n-label="@@costs.page.filterLabel"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [chips]="chips()"
        (chipRemoved)="removeChip($event)"
        (cleared)="f.clear()"
        [presets]="presets()"
        (presetSelected)="setPeriod($any($event))"
      >
        <nz-range-picker
          [ngModel]="pickerValue()"
          (ngModelChange)="setCustom($event)"
          [nzAllowClear]="false"
          nzFormat="yyyy-MM-dd"
          aria-label="Date range"
          i18n-aria-label="@@costs.page.dateRange"
          style="width: 240px"
        />
        <nz-select
          [ngModel]="f.values().projectId"
          (ngModelChange)="f.set({ projectId: $event })"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="All projects"
          i18n-nzPlaceHolder="@@costs.page.allProjects"
          style="width: 180px"
          aria-label="Filter by project"
          i18n-aria-label="@@costs.page.byProject"
        >
          @for (p of projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' · ' + p.name" />
          }
        </nz-select>
      </bo-filter-bar>

      @if (!summary() && summaryLoading()) {
        <bo-skeleton
          variant="cards"
          [rows]="2"
          label="Loading costs"
          i18n-label="@@costs.page.loading"
        />
      } @else if (summary(); as s) {
        <div class="overview">
          <section class="card total" aria-labelledby="total-label">
            <div class="total-head">
              <div>
                <div class="eyebrow" id="total-label" i18n="@@costs.total.eyebrow">
                  Total · {{ periodText() }}
                </div>
                <div
                  class="figure"
                  [attr.title]="s.total?.amount + ' ' + (s.total?.currency ?? 'USD')"
                >
                  {{ totalText() }}
                </div>
                @if (f.values().projectId) {
                  <div class="scope" i18n="@@costs.total.scope">
                    for {{ projectName(f.values().projectId) }}
                  </div>
                }
              </div>
              <dl class="stats">
                <div>
                  <dt i18n="@@costs.total.calls">Calls</dt>
                  <dd>{{ num(calls()) }}</dd>
                </div>
                <div>
                  <dt i18n="@@costs.total.inputTokens">Input tokens</dt>
                  <dd>{{ tokens(s.totalUsage?.inputTokens) }}</dd>
                </div>
                <div>
                  <dt i18n="@@costs.total.outputTokens">Output tokens</dt>
                  <dd>{{ tokens(s.totalUsage?.outputTokens) }}</dd>
                </div>
                <div>
                  <dt i18n="@@costs.total.cacheRead">Cache read</dt>
                  <dd>{{ tokens(s.totalUsage?.cacheReadTokens) }}</dd>
                </div>
              </dl>
            </div>
            <div class="trend">
              <div class="trend-head">
                <h2 i18n="@@costs.trend.title">Cost per day</h2>
                @if (peakDay(); as p) {
                  <span class="hint" i18n="@@costs.trend.peak"
                    >peak {{ p.day }} · {{ cost(p.amount) }}</span
                  >
                }
              </div>
              @defer (on viewport) {
                <div
                  echarts
                  [options]="trend()"
                  class="trend-chart"
                  role="img"
                  [attr.aria-label]="trendLabel()"
                ></div>
              } @placeholder {
                <div class="trend-chart placeholder" aria-hidden="true"></div>
              }
            </div>
          </section>

          <section class="card breakdown" aria-labelledby="bd-title">
            <header class="bd-head">
              <h2 id="bd-title">{{ groupTitle() }}</h2>
              <nz-select
                [ngModel]="groupBy()"
                (ngModelChange)="f.set({ groupBy: $event === 'MODEL' ? null : $event })"
                nzSize="small"
                style="width: 150px"
                aria-label="Group costs by"
                i18n-aria-label="@@costs.breakdown.groupBy"
              >
                @for (g of groups; track g.id) {
                  <nz-option [nzValue]="g.id" [nzLabel]="g.title" />
                }
              </nz-select>
            </header>
            @if (!rows().length) {
              <bo-empty
                tone="quiet"
                title="No usage in this period."
                i18n-title="@@costs.breakdown.empty"
              />
            } @else {
              <ol class="bars" [class.days]="groupBy() === 'DAY'">
                @for (r of rows(); track r.key) {
                  <li [class.other]="r.other">
                    <div class="bar-line">
                      <span class="bar-label" [attr.title]="r.label">{{ r.label }}</span>
                      <bo-cost [value]="r.cost" [usage]="r.usage" />
                      <span class="share">{{ share(r.share) }}%</span>
                    </div>
                    <div class="track" aria-hidden="true">
                      <span [style.width.%]="r.bar"></span>
                    </div>
                    <div class="bar-meta">
                      {{ callsText(r.calls) }}{{ r.usage ? ' · ' + usage(r.usage) : '' }}
                    </div>
                  </li>
                }
              </ol>
            }
          </section>
        </div>
      }

      <section class="ledger" aria-labelledby="ledger-title">
        <header class="ledger-head">
          <h2 id="ledger-title" i18n="@@costs.ledger.title">Ledger</h2>
          <span class="hint">{{ ledgerHint() }}</span>
        </header>
        @if (entriesLoading() && !entriesLoaded()) {
          <bo-skeleton
            variant="table"
            [rows]="8"
            [columns]="6"
            label="Loading the ledger"
            i18n-label="@@costs.ledger.loading"
          />
        } @else if (!ledger().length) {
          <bo-empty
            [title]="f.values().q || f.values().projectId ? text.noMatch : text.noUsage"
            [detail]="f.values().q || f.values().projectId ? null : text.noUsageDetail"
            [actionLabel]="f.values().q || f.values().projectId ? text.clearFilters : text.show90"
            [primary]="false"
            (action)="
              f.values().q || f.values().projectId
                ? f.set({ q: null, projectId: null })
                : setPeriod('90d')
            "
            help="costs"
            guideLabel="How costs are recorded"
            i18n-guideLabel="@@costs.ledger.guide"
          />
        } @else {
          <nz-table
            #lt
            [nzData]="ledger()"
            [nzLoading]="entriesLoading() && entriesLoaded()"
            [nzFrontPagination]="false"
            [nzTotal]="entriesTotal()"
            [nzPageIndex]="page() + 1"
            [nzPageSize]="size()"
            [nzShowSizeChanger]="true"
            [nzPageSizeOptions]="[50, 100, 200]"
            [nzVirtualItemSize]="virtual() ? 41 : 0"
            [nzVirtualForTrackBy]="trackEntry"
            [nzScroll]="virtual() ? { y: '60vh' } : {}"
            (nzPageIndexChange)="setPage($event - 1)"
            (nzPageSizeChange)="setSize($event)"
            nzSize="middle"
          >
            <thead>
              <tr>
                <th
                  nzShowSort
                  [boSort]="sort"
                  boSortKey="occurredAt"
                  nzWidth="166px"
                  i18n="@@costs.ledger.when"
                >
                  When
                </th>
                <th nzShowSort [boSort]="sort" boSortKey="model" i18n="@@costs.ledger.model">
                  Model
                </th>
                <th
                  nzShowSort
                  [boSort]="sort"
                  boSortKey="kind"
                  nzWidth="276px"
                  i18n="@@costs.ledger.work"
                >
                  Work
                </th>
                <th
                  nzShowSort
                  [boSort]="sort"
                  boSortKey="tokens"
                  nzWidth="186px"
                  i18n="@@costs.ledger.tokens"
                >
                  Tokens
                </th>
                <th
                  nzShowSort
                  [boSort]="sort"
                  boSortKey="duration"
                  nzWidth="106px"
                  nzAlign="right"
                  i18n="@@costs.ledger.duration"
                >
                  Duration
                </th>
                <th
                  nzShowSort
                  [boSort]="sort"
                  boSortKey="cost"
                  nzWidth="116px"
                  nzAlign="right"
                  i18n="@@costs.ledger.cost"
                >
                  Cost
                </th>
              </tr>
            </thead>
            <tbody>
              @if (virtual()) {
                <ng-template nz-virtual-scroll let-e let-i="index">
                  <ng-container *ngTemplateOutlet="entryRow; context: { $implicit: e, i: i }" />
                </ng-template>
              } @else {
                @for (e of lt.data; track e.id; let i = $index) {
                  <ng-container *ngTemplateOutlet="entryRow; context: { $implicit: e, i: i }" />
                }
              }
            </tbody>
          </nz-table>
        }
      </section>
    </div>

    <ng-template #entryRow let-e let-i="i">
      <tr
        [class.clickable-row]="!!link(e)?.link"
        [class.active]="nav.cursor() === i"
        [attr.data-nav-index]="i"
        [attr.tabindex]="link(e)?.link ? 0 : -1"
        (click)="openEntry(e)"
        (keydown.enter)="openEntry(e)"
      >
        <td>
          <span class="mono-cell" [attr.title]="abs(e.occurredAt)">{{ when(e.occurredAt) }}</span>
          <div class="sub">{{ ago(e.occurredAt) }}</div>
        </td>
        <td>
          <div class="cell-main">
            <span class="mono-cell ink">{{ e.modelRef?.modelId ?? '-' }}</span>
            <span class="sub">{{ provider(e.modelRef?.provider) }}</span>
          </div>
        </td>
        <td>
          <div class="cell-main">
            <span class="ink-2"
              >{{ kind(e.context?.kind)
              }}{{ e.context?.stepId ? ' · ' + e.context?.stepId : '' }}</span
            >
            @if (link(e); as l) {
              @if (l.link) {
                <a class="sub mono" [routerLink]="l.link" (click)="$event.stopPropagation()">{{
                  l.text
                }}</a>
              } @else {
                <span class="sub mono">{{ l.text }}</span>
              }
            }
          </div>
        </td>
        <td>
          <span class="sub nowrap" [attr.title]="usage(e.usage) || null">{{
            compact(e.usage) || '-'
          }}</span>
        </td>
        <td nzAlign="right">
          <span class="mono-cell">{{ duration(e.durationMs) }}</span>
        </td>
        <td nzAlign="right">
          <bo-cost [value]="e.cost" [usage]="e.usage" />
          @if (e.estimatedCost && e.estimatedCost.amount !== e.cost?.amount) {
            <div class="sub" i18n="@@costs.ledger.estimated">
              est. {{ cost(e.estimatedCost.amount) }}
            </div>
          }
        </td>
      </tr>
    </ng-template>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .overview {
        display: grid;
        grid-template-columns: minmax(0, 3fr) minmax(320px, 2fr);
        gap: 16px;
        margin-bottom: 24px;
      }
      @media (max-width: 1100px) {
        .overview {
          grid-template-columns: 1fr;
        }
      }
      .card.total {
        display: flex;
        flex-direction: column;
      }
      .card {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        padding: 16px 20px;
        min-width: 0;
      }
      .total-head {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: 24px;
        flex-wrap: wrap;
      }
      .eyebrow {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .figure {
        font-family: var(--bo-font-mono);
        font-size: 40px;
        font-weight: 600;
        line-height: 1.1;
        letter-spacing: -0.02em;
        font-variant-numeric: tabular-nums;
        margin-top: 4px;
      }
      .scope {
        font-size: 12px;
        color: var(--bo-ink-2);
        margin-top: 2px;
      }
      .stats {
        display: grid;
        grid-template-columns: repeat(2, auto);
        gap: 8px 28px;
        margin: 4px 0 0;
      }
      .stats dt {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .stats dd {
        margin: 1px 0 0;
        font-family: var(--bo-font-mono);
        font-size: 14px;
        font-variant-numeric: tabular-nums;
      }
      .trend {
        flex: 1;
        display: flex;
        flex-direction: column;
        margin-top: 16px;
        padding-top: 12px;
        border-top: 1px solid var(--bo-line);
      }
      .trend-head,
      .bd-head,
      .ledger-head {
        display: flex;
        align-items: baseline;
        gap: 12px;
      }
      h2 {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
      }
      .hint {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .trend-chart {
        flex: 1;
        min-height: 160px;
        height: 180px;
        margin-top: 4px;
      }
      .trend-chart.placeholder {
        background: var(--bo-surface-2);
        border-radius: var(--bo-radius-control);
      }
      .bd-head {
        justify-content: space-between;
        align-items: center;
        margin-bottom: 12px;
      }
      .bars {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .bars.days {
        gap: 6px;
        max-height: 420px;
        overflow: auto;
      }
      .bar-line {
        display: flex;
        align-items: baseline;
        gap: 10px;
        font-size: 13px;
      }
      .bar-label {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: var(--bo-ink);
      }
      .other .bar-label {
        color: var(--bo-ink-3);
      }
      .share {
        width: 44px;
        text-align: right;
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .track {
        height: 6px;
        margin-top: 4px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        overflow: hidden;
      }
      .track span {
        display: block;
        height: 100%;
        border-radius: 999px;
        background: var(--bo-accent);
      }
      .other .track span {
        background: var(--bo-idle);
      }
      .bar-meta {
        margin-top: 3px;
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .ledger-head {
        margin-bottom: 10px;
      }
      .sub {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .nowrap {
        white-space: nowrap;
      }
      .sub.mono {
        font-family: var(--bo-font-mono);
      }
      .ink {
        color: var(--bo-ink);
      }
    `,
  ],
})
export class CostsPageComponent {
  private readonly api = inject(UsageApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly router = inject(Router);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  private readonly chartTokens = chartTokensSignal();

  readonly f = urlFilters({
    period: 'string',
    from: 'string',
    to: 'string',
    groupBy: 'string',
    projectId: 'string',
    q: 'string',
  });
  /** Ledger column order, sorted by the server (`?ledgerSort=cost.desc`). */
  readonly sort = serverSort(['occurredAt', 'model', 'kind', 'tokens', 'duration', 'cost'], {
    param: 'ledgerSort',
  });
  readonly groups = GROUPS;
  readonly projects = signal<Project[]>([]);
  readonly summary = signal<UsageSummary | null>(null);
  readonly daily = signal<UsageSummary | null>(null);
  readonly summaryLoading = signal(false);
  readonly entries = signal<UsageEntry[]>([]);
  readonly entriesTotal = signal(0);
  readonly entriesLoading = signal(false);
  readonly entriesLoaded = signal(false);
  readonly page = signal(0);
  readonly size = signal(50);

  readonly cost = (v: number) => formatCost(v);
  readonly tokens = (v: number | null | undefined) => (v ? formatTokens(v) : '0');
  readonly usage = formatUsage;
  readonly compact = compactUsage;
  readonly duration = formatDuration;
  readonly kind = contextLabel;
  readonly link = ledgerLink;
  readonly ago = timeAgo;
  readonly abs = absoluteTime;
  readonly trackEntry = (_: number, e: UsageEntry) => e.id;
  readonly provider = (p: string | null | undefined) => {
    if (!p) return '-';
    const brand: Record<string, string> = {
      OPENAI: 'OpenAI',
      OPENAI_COMPATIBLE: $localize`:@@costs.provider.openaiCompatible:OpenAI-compatible`,
    };
    return brand[p] ?? p.charAt(0) + p.slice(1).toLowerCase();
  };

  readonly range = computed(() => {
    const v = this.f.values();
    return rangeFor(v.period, v.from, v.to);
  });
  readonly groupBy = computed<UsageGroupBy>(() => {
    const g = this.f.values().groupBy as UsageGroupBy | null;
    return g && GROUPS.some((x) => x.id === g) ? g : 'MODEL';
  });
  readonly groupTitle = computed(() => GROUPS.find((g) => g.id === this.groupBy())!.title);
  readonly pickerValue = computed<[Date, Date]>(() => [this.range().from, this.range().to]);
  readonly periodText = computed(() => {
    const r = this.range();
    const words: Record<string, string> = {
      '7d': $localize`:@@costs.period.last7:last 7 days`,
      '30d': $localize`:@@costs.period.last30:last 30 days`,
      month: $localize`:@@costs.period.thisMonth:this month`,
      '90d': $localize`:@@costs.period.last90:last 90 days`,
    };
    return words[r.period] ? `${words[r.period]}, ${rangeLabel(r)}` : rangeLabel(r);
  });
  readonly totalText = computed(() => formatCost(this.summary()?.total ?? 0));
  readonly calls = computed(() =>
    (this.summary()?.groups ?? []).reduce((a, g) => a + (g.calls ?? 0), 0),
  );
  readonly rows = computed(() =>
    breakdownRows(this.summary()?.groups, {
      total: this.summary()?.total,
      max: 8,
      chronological: this.groupBy() === 'DAY',
    }),
  );
  readonly series = computed(() => dailySeries(this.daily()?.groups, this.range()));
  readonly peakDay = computed(() => {
    const s = this.series();
    const top = s.reduce<(typeof s)[number] | null>(
      (a, p) => (!a || p.amount > a.amount ? p : a),
      null,
    );
    return top && top.amount > 0 ? top : null;
  });
  readonly trend = computed(() => trendOption(this.series(), this.chartTokens()));
  readonly trendLabel = computed(() => {
    const p = this.peakDay();
    const days = this.series().length;
    return p
      ? $localize`:@@costs.trend.labelPeak:Bar chart of the cost per day, ${days}:days: days; highest on ${p.day}:day: with ${formatCost(p.amount)}:cost:.`
      : $localize`:@@costs.trend.label:Bar chart of the cost per day, ${days}:days: days.`;
  });
  readonly ledger = computed(() =>
    filterLedger(this.entries(), {
      query: this.f.values().q,
      projectId: this.f.values().projectId,
    }),
  );
  readonly hiddenCount = computed(() => this.entries().length - this.ledger().length);
  readonly virtual = computed(() => this.ledger().length > VIRTUAL_FROM);
  readonly presets = computed<FilterPreset[]>(() =>
    PERIODS.map((p) => ({ label: p.label, value: p.id, active: this.range().period === p.id })),
  );
  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    if (this.range().period === 'custom')
      out.push({ id: 'range', label: rangeLabel(this.range()) });
    if (v.projectId)
      out.push({
        id: 'projectId',
        label: $localize`:@@costs.chip.project:Project: ${this.projectName(v.projectId)}:project:`,
      });
    return out;
  });

  readonly nav = listNavigation({
    items: this.ledger,
    open: (e) => this.openEntry(e),
    group: $localize`:@@costs.ledger.shortcutGroup:Ledger`,
  });
  readonly reload = () => {
    this.loadSummary();
    this.loadEntries();
  };
  readonly menu = [
    { label: $localize`:@@costs.page.reload:Reload costs`, icon: 'reload', run: this.reload },
  ];
  readonly text = {
    noMatch: $localize`:@@costs.ledger.noMatch:No ledger entry on this page matches the filters.`,
    noUsage: $localize`:@@costs.ledger.noUsage:No usage recorded in this period.`,
    noUsageDetail: $localize`:@@costs.ledger.noUsageDetail:Workflow steps, code reviews, benchmarks and model tests write here as they call models.`,
    clearFilters: $localize`:@@costs.ledger.clearFilters:Clear filters`,
    show90: $localize`:@@costs.ledger.show90:Show 90 days`,
  };
  readonly ledgerHint = computed(() => {
    const entries = icu(
      $localize`:@@costs.ledger.entries:{count, plural, one {# entry in the period} other {# entries in the period}}`,
      { count: this.entriesTotal() },
    );
    const hidden = this.hiddenCount();
    return hidden
      ? entries +
          ' · ' +
          icu(
            $localize`:@@costs.ledger.hidden:{count, plural, other {# on this page hidden by filters}}`,
            {
              count: hidden,
            },
          )
      : entries;
  });

  num(n: number): string {
    return formatNumber(n);
  }

  share(value: number): string {
    return fixed(value, value < 10 ? 1 : 0);
  }

  callsText(n: number): string {
    return icu($localize`:@@costs.breakdown.calls:{count, plural, one {# call} other {# calls}}`, {
      count: n,
    });
  }

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@costs.page.crumb:Costs` }]);
    this.projectsApi
      .list()
      .pipe(catchError(() => of([] as Project[])))
      .subscribe((p) => this.projects.set(p));
    effect(() => {
      this.range();
      this.groupBy();
      this.f.values().projectId;
      untracked(() => this.loadSummary());
    });
    effect(() => {
      this.range();
      this.sort.query(); // a new column order starts again at page 0
      untracked(() => {
        this.page.set(0);
        this.loadEntries();
      });
    });
    inject(ShortcutsService).register(
      {
        keys: 'f',
        description: $localize`:@@costs.page.shortcut.search:Search the ledger`,
        group: $localize`:@@costs.page.shortcut.group:Costs`,
        handler: () => this.bar()?.focus(),
      },
      inject(DestroyRef),
    );
  }

  projectName(id: string | null | undefined): string {
    const p = this.projects().find((x) => x.id === id);
    return p
      ? `${p.key} · ${p.name}`
      : $localize`:@@costs.page.selectedProject:the selected project`;
  }

  setPeriod(period: string): void {
    this.f.set({ period: period === '30d' ? null : period, from: null, to: null });
  }

  setCustom(range: Date[] | null): void {
    if (!range || range.length !== 2 || !range[0] || !range[1]) return;
    this.f.set({ period: null, from: isoDay(range[0]), to: isoDay(range[1]) });
  }

  removeChip(id: string): void {
    if (id === 'range') this.f.set({ from: null, to: null });
    else if (id === 'projectId') this.f.set({ projectId: null });
    else if (id === 'groupBy') this.f.set({ groupBy: null });
  }

  setPage(page: number): void {
    this.page.set(page);
    this.loadEntries();
  }

  setSize(size: number): void {
    this.size.set(size);
    this.page.set(0);
    this.loadEntries();
  }

  openEntry(e: UsageEntry): void {
    const l = ledgerLink(e);
    if (l?.link) void this.router.navigate(l.link);
  }

  when(iso: string | null | undefined): string {
    if (!iso) return '-';
    const d = new Date(iso);
    return `${isoDay(d).slice(5)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }

  loadSummary(): void {
    const r = apiRange(this.range());
    const projectId = this.f.values().projectId;
    const groupBy = this.groupBy();
    this.summaryLoading.set(true);
    this.api.summary({ ...r, groupBy, projectId }).subscribe({
      next: (s) => {
        this.summary.set(s);
        this.summaryLoading.set(false);
        if (groupBy === 'DAY') this.daily.set(s);
      },
      error: () => this.summaryLoading.set(false),
    });
    if (groupBy !== 'DAY')
      this.api
        .summary({ ...r, groupBy: 'DAY', projectId })
        .pipe(catchError(() => of(null)))
        .subscribe((s) => this.daily.set(s));
  }

  loadEntries(): void {
    const r = apiRange(this.range());
    this.entriesLoading.set(true);
    this.api
      .entries({ ...r, page: this.page(), size: this.size(), sort: this.sort.query() ?? undefined })
      .subscribe({
        next: (p) => {
          this.entries.set(p.content ?? []);
          this.entriesTotal.set(p.totalElements ?? p.content?.length ?? 0);
          this.entriesLoading.set(false);
          this.entriesLoaded.set(true);
        },
        error: () => {
          this.entriesLoading.set(false);
          this.entriesLoaded.set(true);
        },
      });
  }
}

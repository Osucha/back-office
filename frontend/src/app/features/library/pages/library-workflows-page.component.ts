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
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { InsightsApi } from '../../../core/api/insights.service';
import type { LibraryWorkflowMetricsRow, LibraryWorkflowSummary } from '../../../core/api/types';
import { WorkflowLibraryApi } from '../../../core/api/workflow-library.service';
import { icu } from '../../../core/i18n/icu';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent, type FilterChip } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import {
  PERIODS,
  type PeriodId,
  formatRate,
  formatWait,
  periodRange,
} from '../../insights/public-api';
import { LibraryNavComponent } from '../components/library-nav.component';
import { type LibraryRow, filterLibraryWorkflows, libraryRows } from '../data/workflow-metrics';

/**
 * Library: workflows shared across projects (ADR 0037) with their headline metrics for a period: how often they
 * succeed and first time right, how long they take and what a success costs. Entries are published from projects;
 * a row opens the detail page (versions, graph, metrics per version / project / step, projects using it).
 */
@Component({
  selector: 'app-library-workflows-page',
  imports: [
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzTooltipModule,
    CostComponent,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
    SortHeaderDirective,
    LibraryNavComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="libraryWorkflows"
        title="Library"
        i18n-title="@@library.workflows.header.title"
        subtitle="Workflows shared across projects: designed once, adopted as linked copies and measured in every project that runs them."
        i18n-subtitle="@@library.workflows.header.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        <app-library-nav
          boHeaderBelow
          active="workflows"
          [counts]="{ workflows: visibleCount() }"
        />
      </bo-page-header>

      <div class="toolbar">
        <bo-filter-bar
          #bar
          placeholder="Search name, key or tag"
          i18n-placeholder="@@library.workflows.filter.placeholder"
          label="Filter library workflows"
          i18n-label="@@library.workflows.filter.label"
          [query]="f.values().q ?? ''"
          (queryChange)="f.set({ q: $event })"
          [chips]="chips()"
          (chipRemoved)="f.set({ deprecated: null })"
          (cleared)="f.clear()"
        >
          <button
            nz-button
            nzSize="small"
            type="button"
            [attr.aria-pressed]="showDeprecated()"
            (click)="f.set({ deprecated: showDeprecated() ? null : 'show' })"
            i18n="@@library.workflows.filter.deprecated"
          >
            Show deprecated
          </button>
        </bo-filter-bar>
        <div
          class="periods"
          role="group"
          aria-label="Period"
          i18n-aria-label="@@library.workflows.period"
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

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="5"
          [columns]="8"
          label="Loading library workflows"
          i18n-label="@@library.workflows.loading"
        />
      } @else if (!filtered().length) {
        @if (f.active()) {
          <bo-empty
            title="No workflow matches these filters."
            i18n-title="@@library.workflows.empty.noMatch"
            actionLabel="Clear filters"
            i18n-actionLabel="@@library.workflows.empty.clear"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No workflows in the library yet."
            i18n-title="@@library.workflows.empty.title"
            detail="Publish a workflow that works well in a project: in the project's Workflows tab open its menu and choose Publish to library. Other projects then add it from the library and its runs are measured here."
            i18n-detail="@@library.workflows.empty.detail"
            help="libraryWorkflows"
            guideLabel="How the workflow library works"
            i18n-guideLabel="@@library.workflows.empty.guide"
          />
        }
      } @else {
        <nz-table
          #t
          [nzData]="sorted()"
          [nzShowPagination]="sorted().length > 50"
          [nzPageSize]="50"
          nzSize="middle"
        >
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@library.workflows.col.workflow">Workflow</th>
              <th nzShowSort [boSort]="sort" boSortKey="usedIn" nzWidth="126px" i18n="@@library.workflows.col.usedIn">Used in</th>
              <th nzShowSort [boSort]="sort" boSortKey="runs" nzWidth="86px" nzAlign="right" i18n="@@library.workflows.col.runs">Runs</th>
              <th nzShowSort [boSort]="sort" boSortKey="success" nzWidth="106px" i18n="@@library.workflows.col.success">Success</th>
              <th nzShowSort [boSort]="sort" boSortKey="firstPass" nzWidth="106px" i18n="@@library.workflows.col.firstPass">First pass</th>
              <th nzShowSort [boSort]="sort" boSortKey="duration" nzWidth="116px" i18n="@@library.workflows.col.duration">Median time</th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="cost"
                nzWidth="126px"
                nzAlign="right"
                i18n="@@library.workflows.col.cost"
              >
                Cost / success
              </th>
              <th nzShowSort [boSort]="sort" boSortKey="published" nzWidth="190px" i18n="@@library.workflows.col.published">Latest version</th>
            </tr>
          </thead>
          <tbody>
            @for (w of t.data; track w.id; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i"
                [attr.data-nav-index]="i"
                tabindex="0"
                (click)="open(w)"
                (keydown.enter)="open(w)"
                [attr.aria-label]="w.name + ', ' + w.key"
              >
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      <span class="cell-title">{{ w.name }}</span>
                      <span class="key">{{ w.key }}</span>
                      <span class="chip outline mono">v{{ w.latestVersion }}</span>
                      @if (w.deprecated) {
                        <span class="chip outline" i18n="@@library.workflows.deprecated"
                          >deprecated</span
                        >
                      }
                      @for (tag of w.tags ?? []; track tag) {
                        <span class="chip">{{ tag }}</span>
                      }
                    </div>
                    @if (w.description) {
                      <span class="cell-sub" [attr.title]="w.description">{{ w.description }}</span>
                    }
                  </div>
                </td>
                <td class="small">{{ usedIn(w) }}</td>
                <td nzAlign="right" class="mono-cell">{{ w.metrics?.runs?.total ?? 0 }}</td>
                <td class="mono-cell">{{ rate(w.metrics?.successRate) }}</td>
                <td class="mono-cell">{{ rate(w.metrics?.firstPassRate) }}</td>
                <td class="mono-cell">{{ wait(w.metrics?.duration?.p50Seconds) }}</td>
                <td nzAlign="right">
                  @if (w.metrics?.runs?.successful) {
                    <bo-cost
                      [value]="w.metrics?.costPerSuccessfulRun"
                      [apiValue]="w.metrics?.apiCostPerSuccessfulRun"
                    />
                  } @else {
                    <span class="quiet">-</span>
                  }
                </td>
                <td>
                  <bo-provenance
                    verb="by"
                    i18n-verb="@@library.workflows.by"
                    [actor]="w.updatedBy"
                    [at]="w.latestPublishedAt"
                  />
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
        align-items: flex-start;
      }
      .toolbar bo-filter-bar {
        flex: 1 1 420px;
      }
      .periods {
        display: inline-flex;
        gap: 4px;
        padding-top: 2px;
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
      .small {
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
    `,
  ],
})
export class LibraryWorkflowsPageComponent {
  private readonly api = inject(WorkflowLibraryApi);
  private readonly insights = inject(InsightsApi);
  private readonly router = inject(Router);
  private readonly bar = viewChild<FilterBarComponent>('bar');

  readonly f = urlFilters({ q: 'string', deprecated: 'string', period: 'string' });
  readonly periods = PERIODS;
  readonly items = signal<LibraryWorkflowSummary[]>([]);
  readonly metrics = signal<LibraryWorkflowMetricsRow[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);

  readonly text = {
    reload: $localize`:@@library.workflows.reload:Reload library workflows`,
  };

  readonly period = computed<PeriodId>(() => (this.f.values().period as PeriodId) || '30d');
  readonly showDeprecated = computed(() => this.f.values().deprecated === 'show');
  readonly rows = computed<LibraryRow[]>(() => libraryRows(this.items(), this.metrics()));
  readonly filtered = computed(() =>
    filterLibraryWorkflows(this.rows(), this.f.values().q, this.showDeprecated()),
  );
  /** Column sorting (URL `?sort=`); the same order for the table and keyboard navigation. */
  readonly sort = tableSort<LibraryRow>({
    name: (w) => w.name,
    usedIn: (w) => w.usage?.projects ?? 0,
    runs: (w) => w.metrics?.runs?.total ?? 0,
    success: (w) => w.metrics?.successRate,
    firstPass: (w) => w.metrics?.firstPassRate,
    duration: (w) => w.metrics?.duration?.p50Seconds,
    cost: (w) => (w.metrics?.runs?.successful ? w.metrics?.costPerSuccessfulRun?.amount : null),
    published: (w) => w.latestPublishedAt,
  });
  readonly sorted = computed(() => this.sort.apply(this.filtered()));
  readonly visibleCount = computed(() => this.items().filter((w) => !w.deprecated).length);
  readonly chips = computed<FilterChip[]>(() =>
    this.showDeprecated()
      ? [
          {
            id: 'deprecated',
            label: $localize`:@@library.workflows.chip.deprecated:Including deprecated`,
          },
        ]
      : [],
  );

  readonly nav = listNavigation({
    items: this.sorted,
    open: (w) => this.open(w),
    group: $localize`:@@library.workflows.group:Library workflows`,
  });
  readonly reload = () => this.load();

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@library.workflows.crumb.library:Library`, link: '/library/templates' },
      { label: $localize`:@@library.workflows.crumb.workflows:Workflows` },
    ]);
    effect(() => {
      this.period();
      untracked(() => this.load());
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@library.workflows.key.search:Search library workflows`,
          group: $localize`:@@library.workflows.group:Library workflows`,
          handler: () => this.bar()?.focus(),
        },
      ],
      inject(DestroyRef),
    );
  }

  load(): void {
    this.loading.set(true);
    const range = periodRange(this.period());
    forkJoin({
      list: this.api.list(true),
      metrics: this.insights
        .libraryWorkflows(range)
        .pipe(catchError(() => of([] as LibraryWorkflowMetricsRow[]))),
    }).subscribe({
      next: ({ list, metrics }) => {
        this.items.set(list);
        this.metrics.set(metrics);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  open(w: LibraryWorkflowSummary): void {
    void this.router.navigate(['/library/workflows', w.id], {
      queryParams: this.f.values().period ? { period: this.f.values().period } : {},
    });
  }

  usedIn(w: LibraryWorkflowSummary): string {
    return icu(
      $localize`:@@library.workflows.usedIn:{count, plural, =0 {not used} one {# project} other {# projects}}`,
      { count: w.usage?.projects ?? 0 },
    );
  }

  rate(v: number | null | undefined): string {
    return formatRate(v);
  }

  wait(v: number | null | undefined): string {
    return formatWait(v);
  }
}

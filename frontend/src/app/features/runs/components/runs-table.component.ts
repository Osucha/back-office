import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { NzTableModule } from 'ng-zorro-antd/table';
import { debounceTime } from 'rxjs';
import { RunsApi } from '../../../core/api/runs.service';
import { icu } from '../../../core/i18n/icu';
import type { RunStatus, WorkflowRunSummary } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { EventStreamService, RUN_EVENT_TYPES } from '../../../core/events/event-stream.service';
import { actorName } from '../../../core/ui/actor.component';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { batonOf } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { RunTrackComponent } from '../../../core/ui/relay/run-track.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { serverSort } from '../../../core/ui/relay/table-sort';
import { filterRuns, statusesForHolders } from '../data/run-filters';

const VIRTUAL_FROM = 100;
const ROW_HEIGHT = 49;

/**
 * Runs table: baton, run (issue key / id, workflow), mini relay track, project, started by, cost
 * and age. Server paging and status filter; holder, search and "started by me" filter the loaded
 * page. More than 100 rows switch to virtual scrolling. `j` / `k` / `Enter` navigate.
 */
@Component({
  selector: 'app-runs-table',
  imports: [
    NgTemplateOutlet,
    NzTableModule,
    BatonComponent,
    CostComponent,
    EmptyComponent,
    RunTrackComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loading() && !loaded()) {
      <bo-skeleton variant="table" [rows]="8" [columns]="6" />
    } @else if (!rows().length) {
      <bo-empty
        [title]="emptyText()"
        [actionLabel]="emptyActionLabel()"
        [primary]="false"
        (action)="emptyAction()()"
        help="runs"
        guideLabel="How runs work"
        i18n-guideLabel="@@runs.table.guide"
      />
    } @else {
      <nz-table
        #t
        class="runs"
        [nzData]="rows()"
        [nzLoading]="loading() && loaded()"
        [nzFrontPagination]="false"
        [nzTotal]="total()"
        [nzPageIndex]="page() + 1"
        [nzPageSize]="size()"
        [nzShowSizeChanger]="true"
        [nzPageSizeOptions]="[20, 50, 100, 200]"
        [nzShowTotal]="totalTpl"
        [nzVirtualItemSize]="virtual() ? rowHeight : 0"
        [nzVirtualForTrackBy]="trackById"
        [nzScroll]="virtual() ? { y: '62vh' } : {}"
        (nzPageIndexChange)="setPage($event - 1)"
        (nzPageSizeChange)="setSize($event)"
        nzSize="middle"
      >
        <ng-template #totalTpl let-total>{{ totalText(total) }}</ng-template>
        <thead>
          <tr>
            <th nzShowSort [boSort]="sort" boSortKey="status" nzWidth="166px" i18n="@@runs.table.holder">Holder</th>
            <th nzShowSort [boSort]="sort" boSortKey="workflow" i18n="@@runs.table.run">Run</th>
            <th nzWidth="22%" i18n="@@runs.table.relay">Relay</th>
            @if (!projectId()) {
              <th nzShowSort [boSort]="sort" boSortKey="project" nzWidth="106px" i18n="@@runs.table.project">Project</th>
            }
            <th nzShowSort [boSort]="sort" boSortKey="startedBy" nzWidth="166px" i18n="@@runs.table.startedBy">Started by</th>
            <th nzShowSort [boSort]="sort" boSortKey="cost" nzWidth="106px" nzAlign="right" i18n="@@runs.table.cost">Cost</th>
            <th nzShowSort [boSort]="sort" boSortKey="createdAt" nzWidth="106px" nzAlign="right" i18n="@@runs.table.age">Age</th>
          </tr>
        </thead>
        <tbody>
          @if (virtual()) {
            <ng-template nz-virtual-scroll let-r let-i="index">
              <ng-container *ngTemplateOutlet="row; context: { $implicit: r, i: i }" />
            </ng-template>
          } @else {
            @for (r of t.data; track r.id; let i = $index) {
              <ng-container *ngTemplateOutlet="row; context: { $implicit: r, i: i }" />
            }
          }
        </tbody>
      </nz-table>
      <ng-template #row let-r let-i="i">
        <tr
          class="clickable-row"
          [class.active]="nav.cursor() === i"
          [attr.data-nav-index]="i"
          tabindex="0"
          (click)="open(r)"
          (keydown.enter)="open(r)"
          [attr.aria-label]="rowLabel(r)"
        >
          <td><bo-baton [baton]="baton(r)" size="sm" /></td>
          <td>
            <div class="run">
              <span class="run-id" [class.mono]="!r.issueKey">{{
                r.issueKey || r.id?.slice(0, 8)
              }}</span>
              <span class="run-wf"
                >{{ r.workflowName }} <span class="muted">v{{ r.workflowVersion }}</span></span
              >
            </div>
          </td>
          <td><bo-run-track [run]="r" /></td>
          @if (!projectId()) {
            <td>
              <span class="mono">{{ r.projectKey }}</span>
            </td>
          }
          <td class="who">{{ who(r) }}</td>
          <td nzAlign="right"><bo-cost [value]="r.cost" [apiValue]="r.apiCost" /></td>
          <td nzAlign="right" class="age" [attr.title]="abs(r.createdAt)">
            {{ ago(r.createdAt) }}
          </td>
        </tr>
      </ng-template>
    }
  `,
  styles: [
    `
      .run {
        display: flex;
        flex-direction: column;
        min-width: 0;
        line-height: 1.35;
      }
      .run-id {
        font-weight: 500;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .run-wf {
        font-size: 12px;
        color: var(--bo-ink-2);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .who,
      .age {
        color: var(--bo-ink-2);
        font-size: 12.5px;
        white-space: nowrap;
      }
      tr.active > td {
        background: var(--bo-accent-soft) !important;
      }
      tr:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
    `,
  ],
})
export class RunsTableComponent {
  private readonly api = inject(RunsApi);
  private readonly events = inject(EventStreamService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  private readonly identity = inject(IdentityService);

  readonly projectId = input<string | null>(null);
  readonly workflowId = input<string | null>(null);
  /** Explicit status filter (API); holders add their statuses. */
  readonly status = input<RunStatus[]>([]);
  readonly holders = input<readonly string[]>([]);
  readonly query = input<string | null>(null);
  readonly startedBy = input<string | null>(null);
  readonly emptyText = input($localize`:@@runs.table.empty:No runs match these filters.`);
  readonly emptyActionLabel = input<string | null>(null);
  readonly emptyAction = input<() => void>(() => undefined);

  readonly raw = signal<WorkflowRunSummary[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly page = signal(0);
  readonly size = signal(this.identity.pageSize());
  readonly rowHeight = ROW_HEIGHT;
  /** Server-side column sort (ADR 0046), kept in `?runSort=` so it does not clash with other tables of the page. */
  readonly sort = serverSort(['status', 'workflow', 'project', 'startedBy', 'cost', 'createdAt'], {
    param: 'runSort',
  });

  readonly rows = computed(() =>
    filterRuns(this.raw(), {
      holders: this.holders(),
      query: this.query(),
      startedBy: this.startedBy(),
      me: this.identity.user()?.id,
    }),
  );
  readonly filteredOut = computed(() => this.raw().length - this.rows().length);
  readonly virtual = computed(() => this.rows().length > VIRTUAL_FROM);
  private readonly apiStatus = computed(() => {
    const explicit = this.status();
    const fromHolders = statusesForHolders(this.holders());
    return [...new Set([...explicit, ...fromHolders])];
  });

  readonly nav = listNavigation({
    items: this.rows,
    open: (r) => this.open(r),
    group: $localize`:@@runs.table.shortcutGroup:Runs`,
  });

  constructor() {
    effect(() => {
      this.projectId();
      this.workflowId();
      this.apiStatus();
      this.sort.query();
      untracked(() => {
        this.page.set(0);
        this.load();
      });
    });
    this.events
      .of(...RUN_EVENT_TYPES)
      .pipe(debounceTime(1500), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load());
  }

  baton = (r: WorkflowRunSummary) => batonOf.run(r);
  who = (r: WorkflowRunSummary) =>
    r.startedBy ? actorName(r.startedBy) : r.trigger?.type === 'CI' ? 'CI' : '-';
  ago = timeAgo;
  abs = absoluteTime;
  trackById = (_: number, r: WorkflowRunSummary) => r.id;

  rowLabel(r: WorkflowRunSummary): string {
    const b = batonOf.run(r);
    const run = r.issueKey || $localize`:@@runs.table.rowRun:Run ${r.id?.slice(0, 8) ?? ''}:id:`;
    return `${run}, ${r.workflowName}, ${b.label}`;
  }

  totalText(total: number): string {
    const runs = icu($localize`:@@runs.table.total:{count, plural, one {# run} other {# runs}}`, {
      count: total,
    });
    const hidden = this.filteredOut();
    if (!hidden) return runs;
    return (
      runs +
      ' · ' +
      icu($localize`:@@runs.table.hidden:{count, plural, other {# hidden by filters}}`, {
        count: hidden,
      })
    );
  }

  open(r: WorkflowRunSummary): void {
    void this.router.navigate(['/runs', r.id]);
  }

  setPage(page: number): void {
    this.page.set(page);
    this.load();
  }

  setSize(size: number): void {
    this.size.set(size);
    this.page.set(0);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api
      .list({
        projectId: this.projectId(),
        workflowId: this.workflowId(),
        status: this.apiStatus(),
        page: this.page(),
        size: this.size(),
        sort: this.sort.query() ?? undefined,
      })
      .subscribe({
        next: (p) => {
          this.raw.set(p.content ?? []);
          this.total.set(p.totalElements ?? p.content?.length ?? 0);
          this.loading.set(false);
          this.loaded.set(true);
        },
        error: () => {
          this.loading.set(false);
          this.loaded.set(true);
        },
      });
  }
}

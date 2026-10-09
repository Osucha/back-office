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
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { FleetApi } from '../../../core/api/fleet.service';
import {
  AGENT_JOB_STATUSES,
  type AgentJob,
  type AgentJobStatus,
  type RunnerPool,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { formatDuration } from '../../../core/ui/pipes/duration.pipe';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { batonOf } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent, type FilterChip } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { absoluteTime } from '../../../core/ui/relay/format';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { AdminNavComponent } from '../../admin/public-api';
import { AgentJobPanelComponent } from '../components/agent-job-panel.component';
import { FleetNavComponent } from '../components/fleet-nav.component';
import {
  JOB_HOLDERS,
  JOB_STATUS_LABELS,
  filterJobs,
  isJobActive,
  jobContext,
  jobDurationMs,
} from '../data/fleet-view';

/**
 * Agent jobs across the fleet: filter bar (search, holder chips, pool, status) in the URL, server
 * paging, and the job side panel (`?job=<id>`) with the effective policy and denied egress.
 */
@Component({
  selector: 'app-agent-jobs-page',
  imports: [
    FormsModule,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    NzTableModule,
    AdminNavComponent,
    AgentJobPanelComponent,
    BatonComponent,
    CostComponent,
    EmptyComponent,
    FilterBarComponent,
    FleetNavComponent,
    PageHeaderComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="fleet.jobs"
        title="Runner fleet"
        i18n-title="@@fleet.title"
        subtitle="Every agent job handed to a runner: where it ran, under which policy, what it cost."
        i18n-subtitle="@@fleet.jobs.subtitle"
      >
        <button
          boHeaderActions
          nz-button
          type="button"
          (click)="load()"
          [nzLoading]="loading() && loaded()"
          aria-label="Refresh agent jobs"
          i18n-aria-label="@@fleet.jobs.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
        <app-fleet-nav boHeaderBelow active="jobs" [counts]="{ jobs: total() }" />
      </bo-page-header>

      <bo-filter-bar
        placeholder="Search run, node, model or reason"
        i18n-placeholder="@@fleet.jobs.search"
        label="Filter agent jobs"
        i18n-label="@@fleet.jobs.filter"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [holders]="holders"
        [selectedHolders]="f.values().holder"
        (selectedHoldersChange)="f.set({ holder: $event })"
        [chips]="chips()"
        (chipRemoved)="removeChip($event)"
        (cleared)="f.clear()"
      >
        <nz-select
          style="width: 180px"
          nzAllowClear
          nzPlaceHolder="All pools"
          i18n-nzPlaceHolder="@@fleet.jobs.allPools"
          aria-label="Filter by pool"
          i18n-aria-label="@@fleet.jobs.filterPool"
          [ngModel]="f.values().poolId"
          (ngModelChange)="f.set({ poolId: $event })"
        >
          @for (p of pools(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="p.name + ' (' + p.id + ')'" />
          }
        </nz-select>
        <nz-select
          style="width: 150px"
          nzAllowClear
          nzPlaceHolder="Any status"
          i18n-nzPlaceHolder="@@fleet.jobs.anyStatus"
          aria-label="Filter by status"
          i18n-aria-label="@@fleet.jobs.filterStatus"
          [ngModel]="f.values().status"
          (ngModelChange)="f.set({ status: $event })"
        >
          @for (s of statuses; track s) {
            <nz-option [nzValue]="s" [nzLabel]="statusLabel[s]" />
          }
        </nz-select>
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="8"
          [columns]="7"
          label="Loading agent jobs"
          i18n-label="@@fleet.jobs.loading"
        />
      } @else if (!rows().length) {
        <bo-empty
          [title]="f.active() ? text.noMatch : text.empty"
          [detail]="f.active() ? null : text.emptyDetail"
          [actionLabel]="f.active() ? text.clearFilters : null"
          (action)="f.clear()"
          help="fleet.jobs"
          guideLabel="How agent jobs run"
          i18n-guideLabel="@@fleet.jobs.guide"
        />
      } @else {
        <nz-table
          #t
          [nzData]="rows()"
          [nzFrontPagination]="false"
          [nzTotal]="total()"
          [nzPageIndex]="page() + 1"
          [nzPageSize]="size"
          [nzLoading]="loading() && loaded()"
          (nzPageIndexChange)="setPage($event - 1)"
          nzSize="middle"
        >
          <thead>
            <tr>
              <th nzWidth="170px" i18n="@@fleet.jobs.col.state">State</th>
              <th i18n="@@fleet.jobs.col.work">Work</th>
              <th nzWidth="170px" i18n="@@fleet.jobs.col.model">Model</th>
              <th nzWidth="110px" i18n="@@fleet.jobs.col.pool">Pool</th>
              <th nzWidth="90px" nzAlign="right" i18n="@@fleet.jobs.col.duration">Duration</th>
              <th nzWidth="90px" nzAlign="right" i18n="@@fleet.jobs.col.cost">Cost</th>
              <th nzWidth="100px" nzAlign="right" i18n="@@fleet.jobs.col.queued">Queued</th>
            </tr>
          </thead>
          <tbody>
            @for (j of t.data; track j.id; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i || panel.value() === j.id"
                [attr.data-nav-index]="i"
                tabindex="0"
                [attr.aria-label]="rowLabel(j)"
                (click)="open(j)"
                (keydown.enter)="open(j)"
              >
                <td><bo-baton size="sm" appearance="plain" [baton]="baton(j)" /></td>
                <td>
                  <div class="cell-main">
                    <span class="cell-line">
                      <span class="cell-title">{{ ctx(j).label }}</span>
                      @if (j.egressDenied?.length) {
                        <span
                          class="egress"
                          [attr.title]="egressTitle(j)"
                          i18n="@@fleet.jobs.deniedCount"
                          >{j.egressDenied!.length, plural,
                            other {{{ j.egressDenied!.length }} denied}
                          }</span
                        >
                      }
                    </span>
                    <span class="cell-sub">{{ j.reason || (j.mode ?? '').toLowerCase() }}</span>
                  </div>
                </td>
                <td class="mono-cell">{{ j.model?.modelId || '-' }}</td>
                <td>
                  <span class="key">{{ j.poolId || '-' }}</span>
                </td>
                <td nzAlign="right" class="mono-cell">{{ duration(j) }}</td>
                <td nzAlign="right"><bo-cost [value]="j.cost" /></td>
                <td nzAlign="right" class="quiet" [attr.title]="abs(j.queuedAt)">
                  {{ ago(j.queuedAt) }}
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>

    <app-agent-job-panel
      [job]="selected()"
      [canCancel]="canManage()"
      [cancelling]="cancelling()"
      (cancelJob)="cancel($event)"
      (closed)="panel.close()"
    />
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .egress {
        font-size: 11px;
        font-weight: 600;
        padding: 0 7px;
        line-height: 18px;
        border-radius: 999px;
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
    `,
  ],
})
export class AgentJobsPageComponent {
  private readonly api = inject(FleetApi);
  private readonly identity = inject(IdentityService);
  private readonly confirm = inject(ConfirmService);
  private readonly message = inject(NzMessageService);

  readonly f = urlFilters({ q: 'string', holder: 'list', poolId: 'string', status: 'string' });
  readonly panel = panelParam('job');
  readonly holders = JOB_HOLDERS;
  readonly statuses = AGENT_JOB_STATUSES;
  readonly statusLabel = JOB_STATUS_LABELS;
  readonly size = 50;

  readonly jobs = signal<AgentJob[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly pools = signal<RunnerPool[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly cancelling = signal(false);
  readonly detail = signal<AgentJob | null>(null);
  readonly canManage = computed(() => this.identity.can('fleet.manage'));
  readonly text = {
    noMatch: $localize`:@@fleet.jobs.noMatch:No agent jobs match these filters.`,
    empty: $localize`:@@fleet.jobs.empty:No agent jobs yet.`,
    emptyDetail: $localize`:@@fleet.jobs.emptyDetail:Jobs appear here when a workflow step or benchmark attempt needs a sandboxed agent.`,
    clearFilters: $localize`:@@fleet.jobs.clearFilters:Clear filters`,
  };

  readonly rows = computed(() => {
    const q = (this.f.values().q ?? '').trim().toLowerCase();
    const list = filterJobs(this.jobs(), this.f.values().holder ?? []);
    if (!q) return list;
    return list.filter((j) =>
      [j.runId, j.nodeId, j.model?.modelId, j.reason, j.id, j.mode].some((v) =>
        v?.toLowerCase().includes(q),
      ),
    );
  });
  readonly selected = computed(() => {
    const id = this.panel.value();
    if (!id) return null;
    const d = this.detail();
    return d?.id === id ? d : (this.jobs().find((j) => j.id === id) ?? null);
  });
  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    if (v.poolId) {
      const pool = v.poolId;
      out.push({ id: 'poolId', label: $localize`:@@fleet.jobs.chip.pool:Pool: ${pool}:pool:` });
    }
    if (v.status) {
      const status = JOB_STATUS_LABELS[v.status as AgentJobStatus] ?? v.status;
      out.push({
        id: 'status',
        label: $localize`:@@fleet.jobs.chip.status:Status: ${status}:status:`,
      });
    }
    return out;
  });
  readonly nav = listNavigation({
    items: this.rows,
    open: (j) => this.open(j),
    group: $localize`:@@fleet.nav.jobs:Agent jobs`,
    when: () => !this.panel.value(),
  });

  baton = (j: AgentJob) => batonOf.agentJob(j.status);
  ctx = jobContext;
  ago = timeAgo;
  abs = absoluteTime;

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@fleet.breadcrumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@fleet.title:Runner fleet`, link: '/admin/fleet' },
      { label: $localize`:@@fleet.nav.jobs:Agent jobs` },
    ]);
    this.api.pools().subscribe({ next: (p) => this.pools.set(p ?? []), error: () => undefined });
    effect(() => {
      this.f.values(); // reload on any filter change (pool, status)
      untracked(() => {
        this.page.set(0);
        this.load();
      });
    });
    effect(() => {
      const id = this.panel.value();
      if (!id) return;
      untracked(() =>
        this.api.job(id).subscribe({ next: (j) => this.detail.set(j), error: () => undefined }),
      );
    });
  }

  removeChip(id: string): void {
    this.f.set(id === 'poolId' ? { poolId: null } : { status: null });
  }

  duration(j: AgentJob): string {
    const ms = jobDurationMs(j);
    return ms === null ? '-' : formatDuration(ms);
  }

  rowLabel(j: AgentJob): string {
    const model = j.model?.modelId ?? $localize`:@@fleet.jobs.noModel:no model`;
    return `${this.baton(j).label}, ${jobContext(j).label}, ${model}`;
  }

  egressTitle(j: AgentJob): string {
    const hosts = (j.egressDenied ?? []).join(', ');
    return $localize`:@@fleet.jobs.egressTitle:Egress denied: ${hosts}:hosts:`;
  }

  open(j: AgentJob): void {
    if (j.id) this.panel.open(j.id);
  }

  setPage(p: number): void {
    this.page.set(p);
    this.load();
  }

  load(): void {
    const v = this.f.values();
    this.loading.set(true);
    this.api
      .jobs({
        poolId: v.poolId ?? undefined,
        status: (v.status as AgentJobStatus | null) ?? undefined,
        page: this.page(),
        size: this.size,
      })
      .subscribe({
        next: (p) => {
          this.jobs.set(p.content ?? []);
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

  async cancel(j: AgentJob): Promise<void> {
    if (!j.id || !isJobActive(j.status)) return;
    const ok = await this.confirm.danger(
      $localize`:@@fleet.jobs.cancel.title:Cancel this agent job?`,
      $localize`:@@fleet.jobs.cancel.text:The runner stops the sandbox at its next heartbeat. The workflow step fails as cancelled; work already pushed stays.`,
      $localize`:@@fleet.jobs.cancel.ok:Cancel job`,
    );
    if (!ok) return;
    this.cancelling.set(true);
    this.api.cancelJob(j.id).subscribe({
      next: () => {
        this.cancelling.set(false);
        this.message.success($localize`:@@fleet.jobs.cancelRequested:Cancellation requested`);

        this.load();
        this.api.job(j.id!).subscribe({ next: (d) => this.detail.set(d), error: () => undefined });
      },
      error: () => this.cancelling.set(false),
    });
  }
}

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
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { FleetApi } from '../../../core/api/fleet.service';
import type { Runner, RunnerPool } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent, type FilterChip } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { absoluteTime } from '../../../core/ui/relay/format';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { AdminNavComponent } from '../../admin/public-api';
import { FleetNavComponent } from '../components/fleet-nav.component';
import { revokeConsequences, runnerBaton } from '../data/fleet-view';

/** Registered runner-controllers: version, backends, runtime classes, load, last heartbeat. */
@Component({
  selector: 'app-fleet-runners-page',
  imports: [
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    NzTableModule,
    AdminNavComponent,
    BatonComponent,
    EmptyComponent,
    FilterBarComponent,
    FleetNavComponent,
    PageHeaderComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="fleet.runners"
        title="Runner fleet"
        i18n-title="@@fleet.title"
        subtitle="Runner-controllers registered with a pool. Each one leases jobs of its pool and renders them into sandboxes."
        i18n-subtitle="@@fleet.runners.subtitle"
      >
        <button
          boHeaderActions
          nz-button
          type="button"
          (click)="load()"
          [nzLoading]="loading() && loaded()"
          aria-label="Refresh runners"
          i18n-aria-label="@@fleet.runners.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
        <app-fleet-nav boHeaderBelow active="runners" [counts]="{ runners: runners().length }" />
      </bo-page-header>

      <bo-filter-bar
        placeholder="Search runner, cluster or version"
        i18n-placeholder="@@fleet.runners.search"
        label="Filter runners"
        i18n-label="@@fleet.runners.filter"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [chips]="chips()"
        (chipRemoved)="f.set({ poolId: null })"
        (cleared)="f.clear()"
      >
        <nz-select
          style="width: 200px"
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
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="4"
          [columns]="7"
          label="Loading runners"
          i18n-label="@@fleet.runners.loading"
        />
      } @else if (!rows().length) {
        <bo-empty
          [title]="f.active() ? text.noMatch : text.empty"
          [detail]="f.active() ? null : text.emptyDetail"
          [actionLabel]="f.active() ? text.clearFilters : text.openPools"
          (action)="f.active() ? f.clear() : openPools()"
          help="fleet.addRunner"
          guideLabel="How to add a runner"
          i18n-guideLabel="@@fleet.runners.guide"
        />
      } @else {
        <nz-table #t [nzData]="rows()" [nzShowPagination]="false" nzSize="middle">
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@fleet.runners.col.runner">
                Runner
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="state"
                nzWidth="140px"
                i18n="@@fleet.runners.col.state"
              >
                State
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="pool"
                nzWidth="120px"
                i18n="@@fleet.runners.col.pool"
              >
                Pool
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="version"
                nzWidth="106px"
                i18n="@@fleet.runners.col.version"
              >
                Version
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="backends"
                i18n="@@fleet.runners.col.backends"
              >
                Backends and runtime classes
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="jobs"
                nzWidth="106px"
                nzAlign="right"
                i18n="@@fleet.runners.col.jobs"
              >
                Jobs
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="lastSeen"
                nzWidth="126px"
                nzAlign="right"
                i18n="@@fleet.runners.col.lastSeen"
              >
                Last seen
              </th>
              <th nzWidth="90px" nzAlign="right">
                <span class="sr-only" i18n="@@fleet.pools.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (r of t.data; track r.id) {
              <tr>
                <td>
                  <div class="cell-main">
                    <span class="cell-title">{{ r.name }}</span>
                    <span class="cell-sub">
                      {{ r.cluster || text.noCluster }} ·
                      <ng-container i18n="@@fleet.runners.registered"
                        >registered {{ ago(r.registeredAt) }}</ng-container
                      >
                    </span>
                  </div>
                </td>
                <td><bo-baton size="sm" appearance="plain" [baton]="baton(r)" /></td>
                <td>
                  <a class="key" [routerLink]="['/admin/fleet/pools', r.poolId]">{{ r.poolId }}</a>
                </td>
                <td class="mono-cell">{{ r.version || '-' }}</td>
                <td>
                  <div class="chips">
                    @for (b of r.backends ?? []; track b) {
                      <span class="chip mono">{{ b }}</span>
                    }
                    @for (c of r.runtimeClasses ?? []; track c) {
                      <span class="chip mono outline">{{ c }}</span>
                    }
                  </div>
                </td>
                <td nzAlign="right" class="mono-cell">
                  {{ r.running ?? 0 }} / {{ r.maxConcurrent ?? '-' }}
                </td>
                <td
                  nzAlign="right"
                  class="age"
                  [attr.title]="abs(r.lastSeenAt)"
                  [class.stale]="!r.online"
                >
                  {{ ago(r.lastSeenAt) }}
                </td>
                <td nzAlign="right">
                  @if (canManage()) {
                    <div class="row-actions">
                      <button
                        nz-button
                        nzSize="small"
                        nzType="text"
                        class="del"
                        type="button"
                        [nzLoading]="revoking() === r.id"
                        (click)="revoke(r)"
                        [attr.aria-label]="revokeLabel(r)"
                        i18n="@@fleet.runners.revoke"
                      >
                        Revoke
                      </button>
                    </div>
                  }
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
      .age {
        font-size: 12.5px;
        color: var(--bo-ink-2);
        white-space: nowrap;
      }
      .age.stale {
        color: var(--bo-blocked-ink);
      }
      a.key {
        text-decoration: none;
      }
      a.key:hover {
        color: var(--bo-accent);
      }
    `,
  ],
})
export class FleetRunnersPageComponent {
  private readonly api = inject(FleetApi);
  private readonly identity = inject(IdentityService);
  private readonly confirm = inject(ConfirmService);
  private readonly message = inject(NzMessageService);
  private readonly router = inject(Router);

  readonly f = urlFilters({ q: 'string', poolId: 'string' });
  readonly runners = signal<Runner[]>([]);
  readonly pools = signal<RunnerPool[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly revoking = signal<string | null>(null);
  readonly canManage = computed(() => this.identity.can('fleet.manage'));
  readonly text = {
    noMatch: $localize`:@@fleet.runners.noMatch:No runners match these filters.`,
    empty: $localize`:@@fleet.runners.empty:No runners registered yet.`,
    emptyDetail: $localize`:@@fleet.runners.emptyDetail:Add a runner from a pool: create a registration token and install the chart.`,
    clearFilters: $localize`:@@fleet.jobs.clearFilters:Clear filters`,
    openPools: $localize`:@@fleet.runners.openPools:Open pools`,
    noCluster: $localize`:@@fleet.runners.noCluster:cluster not reported`,
  };
  readonly filtered = computed(() => {
    const q = (this.f.values().q ?? '').trim().toLowerCase();
    const list = [...this.runners()].sort(
      (a, b) =>
        Number(!!b.online) - Number(!!a.online) || (a.name ?? '').localeCompare(b.name ?? ''),
    );
    if (!q) return list;
    return list.filter((r) =>
      [r.name, r.cluster, r.version, r.poolId].some((v) => v?.toLowerCase().includes(q)),
    );
  });
  /** Column sorting (URL `?sort=`); without one, online runners first, then by name. */
  readonly sort = tableSort<Runner>({
    name: (r) => r.name,
    state: (r) => runnerBaton(r).label,
    pool: (r) => r.poolId,
    version: (r) => r.version,
    backends: (r) => [...(r.backends ?? []), ...(r.runtimeClasses ?? [])].join(' '),
    jobs: (r) => r.running ?? 0,
    lastSeen: (r) => r.lastSeenAt,
  });
  readonly rows = computed(() => this.sort.apply(this.filtered()));
  readonly chips = computed<FilterChip[]>(() => {
    const id = this.f.values().poolId;
    if (!id) return [];
    const pool = this.pools().find((p) => p.id === id)?.name ?? id;
    return [{ id: 'poolId', label: $localize`:@@fleet.jobs.chip.pool:Pool: ${pool}:pool:` }];
  });

  baton = runnerBaton;
  ago = (iso: string | null | undefined) =>
    iso ? timeAgo(iso) : $localize`:@@fleet.runners.never:never`;
  abs = absoluteTime;

  private readonly nav = inject(BreadcrumbService);

  constructor() {
    this.nav.set([
      { label: $localize`:@@fleet.breadcrumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@fleet.title:Runner fleet`, link: '/admin/fleet' },
      { label: $localize`:@@fleet.nav.runners:Runners` },
    ]);
    this.api.pools().subscribe({ next: (p) => this.pools.set(p ?? []), error: () => undefined });
    effect(() => {
      this.f.values().poolId;
      untracked(() => this.load());
    });
  }

  openPools(): void {
    void this.router.navigate(['/admin/fleet/pools']);
  }

  load(): void {
    this.loading.set(true);
    this.api.runners(this.f.values().poolId).subscribe({
      next: (list) => {
        this.runners.set(list ?? []);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  revokeLabel(r: Runner): string {
    const name = r.name ?? '';
    return $localize`:@@fleet.runners.revokeLabel:Revoke ${name}:runner:`;
  }

  async revoke(r: Runner): Promise<void> {
    if (!r.id) return;
    const name = r.name ?? '';
    const ok = await this.confirm.danger(
      $localize`:@@fleet.runners.revoke.title:Revoke runner ${name}:runner:?`,
      revokeConsequences(r),
      $localize`:@@fleet.runners.revoke.ok:Revoke runner`,
    );
    if (!ok) return;
    this.revoking.set(r.id);
    this.api.revokeRunner(r.id).subscribe({
      next: () => {
        this.revoking.set(null);
        this.message.success($localize`:@@fleet.runners.revoked:Runner ${name}:runner: revoked`);

        this.load();
      },
      error: () => this.revoking.set(null),
    });
  }
}

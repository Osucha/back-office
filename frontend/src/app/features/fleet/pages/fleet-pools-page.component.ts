import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { FleetApi } from '../../../core/api/fleet.service';
import type { RunnerPool } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { icu } from '../../../core/i18n/icu';
import { AdminNavComponent } from '../../admin/public-api';
import { AddRunnerPanelComponent } from '../components/add-runner-panel.component';
import { FleetNavComponent } from '../components/fleet-nav.component';
import { poolBaton } from '../data/fleet-view';
import { formatLabels, isolationName } from '../data/policy';

/**
 * Runner pools: capacity (online runners, queued, running, the isolation ceiling), the pool editor
 * and "Add runner" (`?addRunner=<poolId>`, registration token + chart values in a side panel).
 */
@Component({
  selector: 'app-fleet-pools-page',
  imports: [
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzTableModule,
    NzTooltipModule,
    AdminNavComponent,
    AddRunnerPanelComponent,
    BatonComponent,
    EmptyComponent,
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
        help="fleet.pools"
        title="Runner fleet"
        i18n-title="@@fleet.title"
        subtitle="Pools of runner-controllers in your clusters. Agents run in sandboxes there; Back Office only hands out signed jobs."
        i18n-subtitle="@@fleet.pools.subtitle"
      >
        <button
          boHeaderActions
          nz-button
          type="button"
          (click)="load()"
          [nzLoading]="loading() && loaded()"
          aria-label="Refresh pools"
          i18n-aria-label="@@fleet.pools.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
        @if (canManage()) {
          <a boHeaderActions nz-button routerLink="/admin/fleet/pools/new" i18n="@@fleet.pools.new"
            >New pool</a
          >
        }
        @if (canManage() && pools().length) {
          <button
            boHeaderActions
            nz-button
            nzType="primary"
            type="button"
            (click)="addRunner.open(pools()[0].id)"
            i18n="@@fleet.pools.addRunner"
          >
            Add runner
          </button>
        }
        <app-fleet-nav boHeaderBelow active="pools" [counts]="{ pools: pools().length }" />
      </bo-page-header>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="3"
          [columns]="7"
          label="Loading pools"
          i18n-label="@@fleet.pools.loading"
        />
      } @else if (!pools().length) {
        <bo-empty
          title="No runner pools yet."
          i18n-title="@@fleet.pools.empty"
          [detail]="canManage() ? text.emptyManage : text.emptyView"
          [actionLabel]="canManage() ? text.newPool : null"
          (action)="router.navigate(['/admin/fleet/pools/new'])"
          help="fleet.pools"
          guideLabel="How runner pools work"
          i18n-guideLabel="@@fleet.pools.guide"
        />
      } @else {
        <section class="totals" aria-label="Fleet totals" i18n-aria-label="@@fleet.pools.totals">
          <div>
            <span class="t-label" i18n="@@fleet.pools.runnersOnline">Runners online</span>
            <span class="t-value"
              >{{ totals().online }}
              <span class="of" i18n="@@fleet.pools.ofRunners">of {{ totals().runners }}</span></span
            >
          </div>
          <div>
            <span class="t-label" i18n="@@fleet.pools.jobsRunning">Jobs running</span>
            <span class="t-value">{{ totals().running }}</span>
          </div>
          <div>
            <span class="t-label" i18n="@@fleet.pools.jobsQueued">Jobs queued</span>
            <span class="t-value" [class.you]="totals().queued > 0">{{ totals().queued }}</span>
          </div>
          <div>
            <span class="t-label" i18n="@@fleet.pools.capacity">Capacity</span>
            <span class="t-value"
              >{{ totals().capacity }}
              <span class="of" i18n="@@fleet.pools.concurrentJobs">{totals().capacity, plural,
                one {concurrent job}
                other {concurrent jobs}
              }</span></span
            >
          </div>
        </section>
        <nz-table #t [nzData]="rows()" [nzShowPagination]="false" nzSize="middle" class="pools">
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@fleet.pools.col.pool">
                Pool
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="state"
                nzWidth="150px"
                i18n="@@fleet.pools.col.state"
              >
                State
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="online"
                nzWidth="146px"
                nzAlign="right"
                i18n="@@fleet.pools.col.runnersOnline"
              >
                Runners online
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="running"
                nzWidth="106px"
                nzAlign="right"
                i18n="@@fleet.pools.col.running"
              >
                Running
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="queued"
                nzWidth="106px"
                nzAlign="right"
                i18n="@@fleet.pools.col.queued"
              >
                Queued
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="ceiling"
                nzWidth="190px"
                i18n="@@fleet.pools.col.ceiling"
              >
                Isolation ceiling
              </th>
              <th nzWidth="150px" nzAlign="right">
                <span class="sr-only" i18n="@@fleet.pools.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (p of t.data; track p.id; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i"
                [attr.data-nav-index]="i"
                tabindex="0"
                [attr.aria-label]="p.name + ', ' + baton(p).label"
                (click)="open(p)"
                (keydown.enter)="open(p)"
              >
                <td>
                  <div class="cell-main">
                    <span class="cell-line">
                      <a
                        class="cell-title"
                        [routerLink]="['/admin/fleet/pools', p.id]"
                        (click)="$event.stopPropagation()"
                        >{{ p.name }}</a
                      >
                      <span class="key">{{ p.id }}</span>
                    </span>
                    <span class="cell-sub">
                      {{ summary(p) }}
                      @if (labels(p)) {
                        · <span class="mono-inline">{{ labels(p) }}</span>
                      }
                    </span>
                  </div>
                </td>
                <td><bo-baton size="sm" appearance="plain" [baton]="baton(p)" /></td>
                <td nzAlign="right" class="mono-cell">
                  {{ p.onlineRunners ?? 0 }} / {{ p.runners ?? 0 }}
                </td>
                <td nzAlign="right" class="mono-cell">{{ p.running ?? 0 }}</td>
                <td nzAlign="right" class="mono-cell" [class.you]="(p.queued ?? 0) > 0">
                  {{ p.queued ?? 0 }}
                </td>
                <td>
                  <span class="chip outline">{{ iso(p.ceiling?.isolation) }}</span>
                </td>
                <td nzAlign="right">
                  <div class="row-actions">
                    @if (canManage()) {
                      <button
                        nz-button
                        nzSize="small"
                        type="button"
                        (click)="$event.stopPropagation(); addRunner.open(p.id)"
                      >
                        <nz-icon nzType="plus" />
                        <ng-container i18n="@@fleet.pools.rowAddRunner">Add runner</ng-container>
                      </button>
                    }
                    <a
                      nz-button
                      nzSize="small"
                      nzType="text"
                      [routerLink]="['/admin/fleet/jobs']"
                      [queryParams]="{ poolId: p.id }"
                      (click)="$event.stopPropagation()"
                      [attr.aria-label]="jobsOf(p)"
                      nz-tooltip
                      nzTooltipTitle="Agent jobs"
                      i18n-nzTooltipTitle="@@fleet.pools.jobsTooltip"
                    >
                      <nz-icon nzType="unordered-list" />
                    </a>
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>

    <app-add-runner-panel
      [pool]="addRunnerPool()"
      [open]="!!addRunner.value() && canManage()"
      (created)="load()"
      (closed)="addRunner.close()"
    />
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .totals {
        display: flex;
        flex-wrap: wrap;
        gap: 12px 40px;
        margin-bottom: 16px;
        padding: 12px 16px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .totals > div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .t-label {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .t-value {
        font-family: var(--bo-font-mono);
        font-size: 18px;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
      }
      .t-value .of {
        font-family: var(--bo-font-ui);
        font-size: 12px;
        font-weight: 400;
        color: var(--bo-ink-3);
      }
      .you {
        color: var(--bo-you-ink);
      }
      .mono-inline {
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
      }
    `,
  ],
})
export class FleetPoolsPageComponent {
  private readonly api = inject(FleetApi);
  private readonly identity = inject(IdentityService);
  readonly router = inject(Router);

  readonly addRunner = panelParam('addRunner');
  readonly pools = signal<RunnerPool[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly canManage = computed(() => this.identity.can('fleet.manage'));
  readonly text = {
    emptyManage: $localize`:@@fleet.pools.emptyManage:Create a pool, then add a runner-controller to it with a registration token.`,
    emptyView: $localize`:@@fleet.pools.emptyView:An administrator with fleet.manage creates pools.`,
    newPool: $localize`:@@fleet.pools.newPool:New pool`,
  };
  /** `?addRunner=<poolId>`; `?addRunner=1` (command palette) picks the first pool. */
  readonly addRunnerPool = computed(() => {
    const v = this.addRunner.value();
    if (!v) return null;
    return this.pools().find((p) => p.id === v) ?? (v === '1' ? (this.pools()[0] ?? null) : null);
  });
  readonly totals = computed(() => {
    const t = { runners: 0, online: 0, running: 0, queued: 0, capacity: 0 };
    for (const p of this.pools()) {
      t.runners += p.runners ?? 0;
      t.online += p.onlineRunners ?? 0;
      t.running += p.running ?? 0;
      t.queued += p.queued ?? 0;
      t.capacity += p.maxConcurrentJobs ?? 0;
    }
    return t;
  });
  /** Column sorting (URL `?sort=`); the same order for the table and keyboard navigation. */
  readonly sort = tableSort<RunnerPool>({
    name: (p) => p.name,
    state: (p) => poolBaton(p).label,
    online: (p) => p.onlineRunners ?? 0,
    running: (p) => p.running ?? 0,
    queued: (p) => p.queued ?? 0,
    ceiling: (p) => isolationName(p.ceiling?.isolation),
  });
  readonly rows = computed(() => this.sort.apply(this.pools()));
  readonly nav = listNavigation({
    items: this.rows,
    open: (p) => this.open(p),
    group: $localize`:@@fleet.nav.pools:Pools`,
    when: () => !this.addRunner.value(),
  });

  baton = poolBaton;
  iso = isolationName;
  labels = (p: RunnerPool) => formatLabels(p.labels);

  summary(p: RunnerPool): string {
    const projects = p.projectIds?.length
      ? icu(
          $localize`:@@fleet.pools.projects:{count, plural, one {# project} other {# projects}}`,
          { count: p.projectIds.length },
        )
      : $localize`:@@fleet.pools.anyProject:Any project`;
    const warm = p.warmPoolSize ?? 0;
    const max = p.maxConcurrentJobs ?? 0;
    return (
      projects +
      ' · ' +
      $localize`:@@fleet.pools.warmMax:warm ${warm}:warm: · max ${max}:max: concurrent`
    );
  }

  jobsOf(p: RunnerPool): string {
    const name = p.name;
    return $localize`:@@fleet.pools.jobsOf:Agent jobs of ${name}:pool:`;
  }

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@fleet.breadcrumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@fleet.title:Runner fleet`, link: '/admin/fleet' },
      { label: $localize`:@@fleet.nav.pools:Pools` },
    ]);
    this.load();
    inject(ShortcutsService).register(
      [
        {
          keys: 'n',
          description: $localize`:@@fleet.pools.newPool:New pool`,
          group: $localize`:@@fleet.nav.pools:Pools`,

          when: () => this.canManage() && !this.addRunner.value(),
          handler: () => void this.router.navigate(['/admin/fleet/pools/new']),
        },
      ],
      inject(DestroyRef),
    );
  }

  open(p: RunnerPool): void {
    void this.router.navigate(['/admin/fleet/pools', p.id]);
  }

  load(): void {
    this.loading.set(true);
    this.api.pools().subscribe({
      next: (list) => {
        this.pools.set(list ?? []);
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

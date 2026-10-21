import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzTableModule } from 'ng-zorro-antd/table';
import { catchError, forkJoin, of } from 'rxjs';
import { OperationsApi } from '../../../core/api/operations.service';
import type { OperationsOverview, WorkItem } from '../../../core/api/types';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { batonOf } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { AdminNavComponent } from '../../admin/public-api';
import {
  REFRESH_MS,
  formatAge,
  queueBaton,
  replicaBaton,
  versionSkew,
  workItemCounts,
  workItemStatusLabel,
} from '../data/operations-view';
import { formatNumber } from '../../../core/i18n/numbers';

/**
 * Operations: the API replicas (roles, version, last heartbeat), the durable work queue (counts per
 * status, oldest ready age, DEAD items with retry), agent jobs per status and SSE subscribers.
 * Refreshes every 10 s while the tab is visible; pauses when it is hidden.
 */
@Component({
  selector: 'app-operations-page',
  imports: [
    NzButtonModule,
    NzIconModule,
    NzTableModule,
    AdminNavComponent,
    BatonComponent,
    EmptyComponent,
    PageHeaderComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.operations"
        title="Operations"
        i18n-title="@@operations.page.title"
        subtitle="Replicas of the API, the work queue behind every run step and the live-update streams."
        i18n-subtitle="@@operations.page.subtitle"
      >
        <span boHeaderMeta class="refresh" aria-live="polite">
          @if (paused()) {
            <ng-container i18n="@@operations.page.paused"
              >Paused while the tab is hidden</ng-container
            >
          } @else if (stale()) {
            <span class="warn" i18n="@@operations.page.stale"
              >Could not refresh; showing data from {{ ago(updatedAt()) }}</span
            >
          } @else if (updatedAt()) {
            <ng-container i18n="@@operations.page.updated"
              >Updated {{ ago(updatedAt()) }} · every 10 s</ng-container
            >
          }
        </span>
        <button
          boHeaderActions
          nz-button
          type="button"
          (click)="refresh()"
          [nzLoading]="loading() && !!overview()"
          aria-label="Refresh now"
          i18n-aria-label="@@operations.page.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
      </bo-page-header>

      @if (!overview() && loading()) {
        <bo-skeleton
          variant="cards"
          [rows]="2"
          label="Loading operations"
          i18n-label="@@operations.page.loading"
        />
      } @else if (!overview()) {
        <bo-empty
          title="The operations overview is not available."
          i18n-title="@@operations.page.unavailable.title"
          detail="The API did not answer /admin/operations/overview. Check that this version of the backend exposes it."
          i18n-detail="@@operations.page.unavailable.detail"
          actionLabel="Try again"
          i18n-actionLabel="@@operations.page.unavailable.retry"
          (action)="refresh()"
        />
      } @else if (overview(); as o) {
        <section class="tiles" aria-label="Summary" i18n-aria-label="@@operations.tiles.label">
          <div class="tile">
            <span class="t-label" i18n="@@operations.tiles.replicas">Replicas</span>
            <span class="t-value">{{ o.replicas?.length ?? 0 }}</span>
            <span class="t-sub">{{ versionsText() }}</span>
          </div>
          <div class="tile">
            <span class="t-label" i18n="@@operations.tiles.workQueue">Work queue</span>
            <bo-baton [baton]="queue()" size="sm" />
            <span class="t-sub" i18n="@@operations.tiles.oldestReady"
              >oldest ready {{ age(o.oldestReadyAgeSeconds) }}</span
            >
          </div>
          <div class="tile">
            <span class="t-label" i18n="@@operations.tiles.deadItems">Dead items</span>
            <span class="t-value" [class.you]="(o.deadItems ?? 0) > 0">{{ o.deadItems ?? 0 }}</span>
            <span class="t-sub" i18n="@@operations.tiles.deadItemsSub"
              >failed after every retry</span
            >
          </div>
          <div class="tile">
            <span class="t-label" i18n="@@operations.tiles.subscribers"
              >Live-update subscribers</span
            >
            <span class="t-value">{{ o.sseSubscribers ?? 0 }}</span>
            <span class="t-sub" i18n="@@operations.tiles.subscribersSub">open SSE streams</span>
          </div>
        </section>

        <div class="cols">
          <section class="card" aria-labelledby="h-replicas">
            <h2 id="h-replicas" i18n="@@operations.replicas.title">Replicas</h2>
            <nz-table [nzData]="replicas()" [nzShowPagination]="false" nzSize="small">
              <thead>
                <tr>
                  <th
                    nzShowSort
                    [boSort]="sort"
                    boSortKey="instance"
                    i18n="@@operations.replicas.instance"
                  >
                    Instance
                  </th>
                  <th
                    nzShowSort
                    [boSort]="sort"
                    boSortKey="state"
                    nzWidth="136px"
                    i18n="@@operations.replicas.state"
                  >
                    State
                  </th>
                  <th
                    nzShowSort
                    [boSort]="sort"
                    boSortKey="roles"
                    i18n="@@operations.replicas.roles"
                  >
                    Roles
                  </th>
                  <th
                    nzShowSort
                    [boSort]="sort"
                    boSortKey="version"
                    nzWidth="106px"
                    i18n="@@operations.replicas.version"
                  >
                    Version
                  </th>
                  <th
                    nzShowSort
                    [boSort]="sort"
                    boSortKey="lastSeen"
                    nzWidth="106px"
                    nzAlign="right"
                    i18n="@@operations.replicas.lastSeen"
                  >
                    Last seen
                  </th>
                </tr>
              </thead>
              <tbody>
                @for (r of replicas(); track r.instance) {
                  <tr>
                    <td class="mono-cell">{{ r.instance }}</td>
                    <td><bo-baton size="sm" appearance="plain" [baton]="replica(r)" /></td>
                    <td>
                      <div class="chips">
                        @for (role of r.roles ?? []; track role) {
                          <span class="chip mono">{{ role }}</span>
                        }
                      </div>
                    </td>
                    <td class="mono-cell">{{ r.version }}</td>
                    <td nzAlign="right" class="quiet" [attr.title]="abs(r.lastSeenAt)">
                      {{ ago(r.lastSeenAt) }}
                    </td>
                  </tr>
                }
              </tbody>
            </nz-table>
          </section>

          <section class="card" aria-labelledby="h-queue">
            <h2 id="h-queue" i18n="@@operations.workItems.title">Work items</h2>
            <dl class="counts">
              @for (c of counts(); track c.status) {
                <div [class.dead]="c.status === 'DEAD' && c.count > 0">
                  <dt>{{ statusLabel(c.status) }}</dt>
                  <dd>{{ num(c.count) }}</dd>
                </div>
              }
            </dl>
            <h2 class="sub" i18n="@@operations.agentJobs.title">Agent jobs</h2>
            <div class="chips">
              @for (s of jobCounts(); track s.status) {
                <span class="job-count">
                  <bo-baton size="sm" appearance="plain" [baton]="jobBaton(s.status)" />
                  <span class="mono">{{ s.count }}</span>
                </span>
              } @empty {
                <span class="quiet" i18n="@@operations.agentJobs.empty">No agent jobs.</span>
              }
            </div>
          </section>
        </div>

        <section class="card" aria-labelledby="h-dead">
          <h2 id="h-dead" i18n="@@operations.dead.title">Dead items</h2>
          @if (!dead().length) {
            <bo-empty
              tone="calm"
              holder="done"
              title="No dead items. Every step was processed."
              i18n-title="@@operations.dead.empty"
            />
          } @else {
            <nz-table [nzData]="deadRows()" [nzShowPagination]="false" nzSize="small">
              <thead>
                <tr>
                  <th
                    nzShowSort
                    [boSort]="deadSort"
                    boSortKey="kind"
                    nzWidth="186px"
                    i18n="@@operations.dead.kind"
                  >
                    Kind
                  </th>
                  <th
                    nzShowSort
                    [boSort]="deadSort"
                    boSortKey="error"
                    i18n="@@operations.dead.lastError"
                  >
                    Last error
                  </th>
                  <th
                    nzShowSort
                    [boSort]="deadSort"
                    boSortKey="attempts"
                    nzWidth="96px"
                    nzAlign="right"
                    i18n="@@operations.dead.attempts"
                  >
                    Attempts
                  </th>
                  <th
                    nzShowSort
                    [boSort]="deadSort"
                    boSortKey="created"
                    nzWidth="126px"
                    nzAlign="right"
                    i18n="@@operations.dead.created"
                  >
                    Created
                  </th>
                  <th nzWidth="100px" nzAlign="right">
                    <span class="sr-only" i18n="@@operations.dead.actions">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                @for (w of deadRows(); track w.id) {
                  <tr>
                    <td>
                      <div class="cell-main">
                        <span class="cell-title mono">{{ w.kind }}</span>
                        <span class="cell-sub mono">{{
                          w.dedupeKey || (w.id ?? '').slice(0, 8)
                        }}</span>
                      </div>
                    </td>
                    <td class="error">{{ w.lastError || '-' }}</td>
                    <td nzAlign="right" class="mono-cell">{{ w.attempts ?? 0 }}</td>
                    <td nzAlign="right" class="quiet" [attr.title]="abs(w.createdAt)">
                      {{ ago(w.createdAt) }}
                    </td>
                    <td nzAlign="right">
                      <button
                        nz-button
                        nzSize="small"
                        type="button"
                        [nzLoading]="retrying() === w.id"
                        (click)="retry(w)"
                        [attr.aria-label]="retryLabel(w)"
                        i18n="@@operations.dead.retry"
                      >
                        Retry
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </nz-table>
          }
        </section>
      }
    </div>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .refresh {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .warn {
        color: var(--bo-blocked-ink);
      }
      .tiles {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: 12px;
        margin-bottom: 16px;
      }
      .tile {
        display: flex;
        flex-direction: column;
        gap: 4px;
        align-items: flex-start;
        padding: 12px 16px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .t-label {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .t-value {
        font-family: var(--bo-font-mono);
        font-size: 22px;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
      }
      .t-value.you {
        color: var(--bo-you-ink);
      }
      .t-sub {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .cols {
        display: grid;
        grid-template-columns: minmax(0, 3fr) minmax(280px, 2fr);
        gap: 16px;
        margin-bottom: 16px;
      }
      @media (max-width: 1100px) {
        .cols {
          grid-template-columns: 1fr;
        }
      }
      .card {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 16px 20px;
        min-width: 0;
        margin-bottom: 16px;
      }
      .cols .card {
        margin-bottom: 0;
      }
      h2 {
        margin: 0 0 12px;
        font-size: 14px;
        font-weight: 600;
      }
      h2.sub {
        margin-top: 20px;
      }
      .counts {
        display: grid;
        grid-template-columns: repeat(4, 1fr);
        gap: 8px;
        margin: 0;
      }
      .counts > div {
        padding: 8px 10px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
      }
      .counts > div.dead {
        background: var(--bo-you-soft);
        box-shadow: inset 0 0 0 1px var(--bo-you);
      }
      .counts dt {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .counts > div.dead dt {
        color: var(--bo-you-ink);
      }
      .counts dd {
        margin: 0;
        font-family: var(--bo-font-mono);
        font-size: 16px;
        font-weight: 600;
      }
      .job-count {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 2px 10px 2px 4px;
        border-radius: 999px;
        box-shadow: inset 0 0 0 1px var(--bo-line);
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .error {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        color: var(--bo-blocked-ink);
        overflow-wrap: anywhere;
      }
    `,
  ],
})
export class OperationsPageComponent {
  private readonly api = inject(OperationsApi);
  private readonly message = inject(NzMessageService);

  readonly overview = signal<OperationsOverview | null>(null);
  readonly dead = signal<WorkItem[]>([]);
  readonly loading = signal(false);
  readonly stale = signal(false);
  readonly paused = signal(typeof document !== 'undefined' && document.hidden);
  readonly updatedAt = signal<string | null>(null);
  readonly retrying = signal<string | null>(null);
  /** Re-renders "updated 5s ago". */
  private readonly tick = signal(0);

  readonly counts = computed(() => workItemCounts(this.overview()));
  readonly queue = computed(() => queueBaton(this.overview()));
  readonly versions = computed(() => versionSkew(this.overview()?.replicas));
  readonly jobCounts = computed(() =>
    Object.entries(this.overview()?.agentJobs ?? {})
      .filter(([, n]) => n > 0)
      .map(([status, count]) => ({ status, count })),
  );

  /** Column sorting of the replicas (URL `?sort=`). */
  readonly sort = tableSort<NonNullable<OperationsOverview['replicas']>[number]>({
    instance: (r) => r.instance,
    state: (r) => replicaBaton(r).label,
    roles: (r) => (r.roles ?? []).join(', '),
    version: (r) => r.version,
    lastSeen: (r) => r.lastSeenAt,
  });
  readonly replicas = computed(() => this.sort.apply(this.overview()?.replicas ?? []));
  /** Column sorting of the dead items (URL `?deadSort=`). */
  readonly deadSort = tableSort<WorkItem>(
    {
      kind: (w) => w.kind,
      error: (w) => w.lastError,
      attempts: (w) => w.attempts ?? 0,
      created: (w) => w.createdAt,
    },
    { param: 'deadSort' },
  );
  readonly deadRows = computed(() => this.deadSort.apply(this.dead()));

  readonly versionsText = computed(() => {
    const v = this.versions();
    return v.length > 1
      ? $localize`:@@operations.tiles.versions:versions: ${v.join(', ')}:versions:`
      : v[0] || '';
  });

  age = formatAge;
  abs = absoluteTime;
  statusLabel = workItemStatusLabel;
  num = (n: number) => formatNumber(n);
  retryLabel = (w: WorkItem) => $localize`:@@operations.dead.retryLabel:Retry ${w.kind}:kind:`;
  replica = (r: NonNullable<OperationsOverview['replicas']>[number]) => {
    this.tick();
    return replicaBaton(r);
  };
  jobBaton = (s: string) => batonOf.agentJob(s as never);
  ago = (iso: string | null | undefined) => {
    this.tick();
    return iso ? timeAgo(iso) : '-';
  };

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@operations.breadcrumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@operations.breadcrumb.operations:Operations` },
    ]);
    this.refresh();
    const timer = setInterval(() => {
      this.tick.update((t) => t + 1);
      if (!document.hidden) this.refresh(true);
    }, REFRESH_MS);
    const onVisibility = () => {
      this.paused.set(document.hidden);
      if (!document.hidden) this.refresh(true);
    };
    document.addEventListener('visibilitychange', onVisibility);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    });
  }

  refresh(background = false): void {
    if (this.loading() && background) return;
    this.loading.set(true);
    forkJoin({
      overview: this.api.overview().pipe(catchError(() => of(null))),
      dead: this.api.workItems({ status: 'DEAD', size: 50 }).pipe(catchError(() => of(null))),
    }).subscribe(({ overview, dead }) => {
      this.loading.set(false);
      if (overview) {
        this.overview.set(overview);
        this.updatedAt.set(new Date().toISOString());
        this.stale.set(false);
      } else this.stale.set(!!this.overview());
      if (dead) this.dead.set(dead.content ?? []);
    });
  }

  retry(w: WorkItem): void {
    if (!w.id) return;
    this.retrying.set(w.id);
    this.api.retry(w.id).subscribe({
      next: () => {
        this.retrying.set(null);
        this.dead.update((l) => l.filter((x) => x.id !== w.id));
        this.message.success($localize`:@@operations.dead.requeued:${w.kind}:kind: re-queued`);
        this.refresh(true);
      },
      error: () => this.retrying.set(null),
    });
  }
}

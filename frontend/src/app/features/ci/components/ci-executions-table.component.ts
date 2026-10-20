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
import { NgTemplateOutlet } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTableModule } from 'ng-zorro-antd/table';
import { debounceTime } from 'rxjs';
import { CiApi } from '../../../core/api/ci.service';
import { icu } from '../../../core/i18n/icu';
import type { CiExecutionSummary } from '../../../core/api/types';
import { EventStreamService } from '../../../core/events/event-stream.service';
import { IdentityService } from '../../../core/auth/identity.service';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { batonOf } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { serverSort } from '../../../core/ui/relay/table-sort';

const VIRTUAL_FROM = 100;

/** Client-side filter of a loaded page (holder of the gate, search in entrypoint / MR / ref / commit). */
export function filterCi(
  rows: readonly CiExecutionSummary[],
  holders: readonly string[],
  query: string | null,
): CiExecutionSummary[] {
  const q = (query ?? '').trim().toLowerCase();
  return rows.filter((x) => {
    if (holders.length && !holders.includes(batonOf.ci(x).holder)) return false;
    if (!q) return true;
    const mr = x.context?.mergeRequest;
    const hay = [
      x.id,
      x.entrypoint,
      x.projectKey,
      x.repositoryKey,
      x.context?.ref,
      x.context?.commitSha,
      mr?.iid ? '!' + mr.iid : null,
      mr?.title,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return q.split(/\s+/).every((t) => hay.includes(t));
  });
}

/**
 * CI executions (`backoffice-ci run`): gate result as a baton, entrypoint and mode, merge request
 * or ref with a link, commit, project, cost and age. Paged on the server; more than 100 rows use
 * virtual scrolling; `j` / `k` / `Enter` navigate.
 */
@Component({
  selector: 'app-ci-executions-table',
  imports: [
    NgTemplateOutlet,
    NzTableModule,
    NzIconModule,
    BatonComponent,
    CostComponent,
    EmptyComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loading() && !loaded()) {
      <bo-skeleton variant="table" [rows]="6" [columns]="6" />
    } @else if (!rows().length) {
      <bo-empty
        [title]="emptyText()"
        [actionLabel]="emptyActionLabel()"
        [primary]="false"
        (action)="emptyAction()()"
        help="ci"
        guideLabel="How to call Back Office from CI"
        i18n-guideLabel="@@ci.table.guide"
      />
    } @else {
      <nz-table
        #t
        [nzData]="rows()"
        [nzLoading]="loading() && loaded()"
        [nzFrontPagination]="false"
        [nzTotal]="total()"
        [nzPageIndex]="page() + 1"
        [nzPageSize]="size()"
        [nzShowSizeChanger]="true"
        [nzPageSizeOptions]="[20, 50, 100, 200]"
        [nzShowTotal]="totalTpl"
        [nzVirtualItemSize]="virtual() ? 49 : 0"
        [nzVirtualForTrackBy]="trackById"
        [nzScroll]="virtual() ? { y: '62vh' } : {}"
        (nzPageIndexChange)="setPage($event - 1)"
        (nzPageSizeChange)="setSize($event)"
        nzSize="middle"
      >
        <ng-template #totalTpl let-total>{{ totalText(total) }}</ng-template>
        <thead>
          <tr>
            <th nzShowSort [boSort]="sort" boSortKey="status" nzWidth="166px" i18n="@@ci.table.gate">Gate</th>
            <th nzShowSort [boSort]="sort" boSortKey="entrypoint" nzWidth="196px" i18n="@@ci.table.entrypoint">Entrypoint</th>
            <th nzShowSort [boSort]="sort" boSortKey="ref" i18n="@@ci.table.mrOrRef">Merge request or ref</th>
            <th nzWidth="100px" i18n="@@ci.table.commit">Commit</th>
            @if (!projectId()) {
              <th nzShowSort [boSort]="sort" boSortKey="project" nzWidth="106px" i18n="@@ci.table.project">Project</th>
            }
            <th nzShowSort [boSort]="sort" boSortKey="cost" nzWidth="106px" nzAlign="right" i18n="@@ci.table.cost">Cost</th>
            <th nzShowSort [boSort]="sort" boSortKey="createdAt" nzWidth="106px" nzAlign="right" i18n="@@ci.table.age">Age</th>
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
        >
          <td><bo-baton [baton]="baton(r)" size="sm" /></td>
          <td>
            <span class="mono">{{ r.entrypoint }}</span>
            @if (r.mode === 'ADVISORY') {
              <span class="mode" i18n="@@ci.table.advisory">advisory</span>
            } @else {
              <span class="mode" i18n="@@ci.table.blocking">blocking</span>
            }
          </td>
          <td class="mr">
            @if (r.repositoryKey) {
              <span
                class="repo-key mono"
                [attr.title]="r.context?.repositoryUrl || null"
                [attr.aria-label]="repositoryLabel(r.repositoryKey)"
                >{{ r.repositoryKey }}</span
              >
            }
            @if (r.context?.mergeRequest; as mr) {
              <span class="mono">!{{ mr.iid }}</span>
              <span class="mr-title">{{ mr.title }}</span>
              @if (mr.url) {
                <a
                  [href]="mr.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  (click)="$event.stopPropagation()"
                  [attr.aria-label]="openMrLabel(mr.iid)"
                >
                  <nz-icon nzType="export" />
                </a>
              }
            } @else {
              <span class="mono">{{ r.context?.ref || '-' }}</span>
            }
          </td>
          <td>
            <span class="mono muted">{{ (r.context?.commitSha || '').slice(0, 8) || '-' }}</span>
          </td>
          @if (!projectId()) {
            <td>
              <span class="mono">{{ r.projectKey }}</span>
            </td>
          }
          <td nzAlign="right"><bo-cost [value]="r.cost" /></td>
          <td nzAlign="right" class="age" [attr.title]="abs(r.createdAt)">
            {{ ago(r.createdAt) }}
          </td>
        </tr>
      </ng-template>
    }
  `,
  styles: [
    `
      .mode {
        margin-left: 8px;
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .mr {
        display: flex;
        gap: 8px;
        align-items: center;
        min-width: 0;
      }
      .repo-key {
        flex: none;
        font-size: 11.5px;
        padding: 0 6px;
        border-radius: 999px;
        background: var(--bo-ci-soft);
        color: var(--bo-ci-ink);
      }
      .mr-title {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
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
export class CiExecutionsTableComponent {
  private readonly api = inject(CiApi);
  private readonly events = inject(EventStreamService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);

  readonly projectId = input<string | null>(null);
  readonly holders = input<readonly string[]>([]);
  readonly query = input<string | null>(null);
  readonly emptyText = input(
    $localize`:@@ci.table.empty:No CI executions yet. Add backoffice-ci to the project pipeline and run an entrypoint.`,
  );
  readonly emptyActionLabel = input<string | null>(null);
  readonly emptyAction = input<() => void>(() => undefined);

  readonly raw = signal<CiExecutionSummary[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly page = signal(0);
  readonly size = signal(inject(IdentityService).pageSize());
  /** Server-side column sort (ADR 0046), kept in `?ciSort=` so it does not clash with other tables of the page. */
  readonly sort = serverSort(['status', 'entrypoint', 'ref', 'project', 'cost', 'createdAt'], {
    param: 'ciSort',
  });

  readonly rows = computed(() => filterCi(this.raw(), this.holders(), this.query()));
  readonly virtual = computed(() => this.rows().length > VIRTUAL_FROM);
  readonly nav = listNavigation({
    items: this.rows,
    open: (r) => this.open(r),
    group: $localize`:@@ci.table.shortcutGroup:CI executions`,
  });

  constructor() {
    effect(() => {
      this.projectId();
      this.sort.query();
      untracked(() => {
        this.page.set(0);
        this.load();
      });
    });
    this.events
      .of('CiExecutionFinished', 'WorkflowRunStarted', 'WorkflowRunFinished')
      .pipe(debounceTime(1000), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load());
  }

  baton = (x: CiExecutionSummary) => batonOf.ci(x);
  ago = timeAgo;

  totalText(total: number): string {
    return icu(
      $localize`:@@ci.table.total:{count, plural, one {# execution} other {# executions}}`,
      { count: total },
    );
  }

  repositoryLabel(key: string): string {
    return $localize`:@@ci.table.repository:Repository ${key}:key:`;
  }

  openMrLabel(iid: number | string | undefined): string {
    return $localize`:@@ci.table.openMr:Open merge request !${iid ?? ''}:iid:`;
  }
  abs = absoluteTime;
  trackById = (_: number, x: CiExecutionSummary) => x.id;

  open(x: CiExecutionSummary): void {
    void this.router.navigate(['/ci', x.id]);
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
      .listExecutions({
        projectId: this.projectId(),
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

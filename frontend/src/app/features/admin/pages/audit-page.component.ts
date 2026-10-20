import { DatePipe, NgTemplateOutlet } from '@angular/common';
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
import { ActivatedRoute, Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule, type NzSelectItemInterface } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { AdminApi } from '../../../core/api/admin.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import { AUDIT_RESULTS, type AuditEntry, type Project } from '../../../core/api/types';
import { ActorComponent, actorName } from '../../../core/ui/actor.component';
import {
  PrincipalPickerComponent,
  type PrincipalOption,
} from '../../../core/ui/principal-picker.component';
import { icu } from '../../../core/i18n/icu';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import {
  FilterBarComponent,
  type FilterChip,
  type FilterPreset,
} from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { absoluteTime } from '../../../core/ui/relay/format';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { serverSort } from '../../../core/ui/relay/table-sort';
import { AdminNavComponent } from '../components/admin-nav.component';
import { AuditEventPanelComponent } from '../components/audit-event-panel.component';
import { StateChipComponent } from '../components/state-chip.component';
import {
  AUDIT_PRESETS,
  operationGroups,
  operationLabel,
  presetActive,
  resultBadge,
} from '../data/audit-operations';
import { auditFiltersFromUrl, toAuditQuery } from '../data/audit-query';

const VIRTUAL_FROM = 100;
const ROW_HEIGHT = 37;

/**
 * Audit log: filters in the URL (operation grouped with "Sign-in and security" first, result,
 * actor, project, time range, entity id), keyset "Load more", virtual scrolling past 100 rows and
 * the entry in a side panel (`?event=<id>`).
 */
@Component({
  selector: 'app-audit-page',
  imports: [
    DatePipe,
    NgTemplateOutlet,
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    NzDatePickerModule,
    ActorComponent,
    AdminNavComponent,
    AuditEventPanelComponent,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    PrincipalPickerComponent,
    SkeletonComponent,
    SortHeaderDirective,
    StateChipComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.audit"
        title="Audit log"
        i18n-title="@@admin.audit.title"
        subtitle="Every sign-in, access change and denied access; searching the log is recorded too."
        i18n-subtitle="@@admin.audit.subtitle"
      >
        <button
          boHeaderActions
          nz-button
          type="button"
          (click)="search()"
          [nzLoading]="loading() && !!entries().length"
          aria-label="Refresh audit log"
          i18n-aria-label="@@admin.audit.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Entity id"
        i18n-placeholder="@@admin.audit.filter.search"
        label="Filter the audit log"
        i18n-label="@@admin.audit.filter.label"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [chips]="chips()"
        (chipRemoved)="removeChip($event)"
        (cleared)="f.clear()"
        [presets]="presets()"
        (presetSelected)="applyPreset($any($event))"
      >
        <nz-select
          style="width: 200px"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="Any operation"
          i18n-nzPlaceHolder="@@admin.audit.filter.anyOperation"
          aria-label="Filter by operation"
          i18n-aria-label="@@admin.audit.filter.operation"
          [nzFilterOption]="matchOperation"
          [ngModel]="f.values().operation"
          (ngModelChange)="f.set({ operation: $event })"
        >
          @for (g of groups; track g.label) {
            <nz-option-group [nzLabel]="g.label">
              @for (op of g.operations; track op) {
                <nz-option [nzValue]="op" [nzLabel]="opLabel(op)" nzCustomContent>
                  <span class="op-opt"
                    >{{ opLabel(op) }} <span class="op-code">{{ op }}</span></span
                  >
                </nz-option>
              }
            </nz-option-group>
          }
        </nz-select>
        <nz-select
          style="width: 120px"
          nzAllowClear
          nzPlaceHolder="Any result"
          i18n-nzPlaceHolder="@@admin.audit.filter.anyResult"
          aria-label="Filter by result"
          i18n-aria-label="@@admin.audit.filter.result"
          [ngModel]="f.values().result"
          (ngModelChange)="f.set({ result: $event })"
        >
          @for (r of results; track r) {
            <nz-option [nzValue]="r" [nzLabel]="resultWord(r)" />
          }
        </nz-select>
        <div style="width: 170px">
          <app-principal-picker
            kind="user"
            placeholder="Any actor"
            i18n-placeholder="@@admin.audit.filter.anyActor"
            ariaLabel="Filter by actor"
            i18n-ariaLabel="@@admin.audit.filter.actor"
            [known]="knownActors()"
            [value]="f.values().actorId"
            (valueChange)="f.set({ actorId: $any($event) })"
          />
        </div>
        <nz-select
          style="width: 120px"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="Any project"
          i18n-nzPlaceHolder="@@admin.audit.filter.anyProject"
          aria-label="Filter by project"
          i18n-aria-label="@@admin.audit.filter.project"
          [ngModel]="f.values().projectId"
          (ngModelChange)="f.set({ projectId: $event })"
        >
          @for (p of projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="p.key" />
          }
        </nz-select>
        <nz-range-picker
          nzShowTime
          [ngModel]="range()"
          (ngModelChange)="setRange($event)"
          aria-label="Time range"
          i18n-aria-label="@@admin.audit.filter.range"
          style="width: 250px"
          [nzPlaceHolder]="text.rangePlaceholder"
        />
      </bo-filter-bar>

      @if (loading() && !entries().length) {
        <bo-skeleton
          variant="table"
          [rows]="10"
          [columns]="6"
          label="Loading audit entries"
          i18n-label="@@admin.audit.loading"
        />
      } @else if (!entries().length) {
        <bo-empty
          [title]="f.active() ? text.emptyFiltered : text.empty"
          [actionLabel]="f.active() ? text.clearFilters : null"
          [primary]="false"
          (action)="f.clear()"
        />
      } @else {
        <nz-table
          #t
          class="audit"
          [nzData]="entries()"
          [nzShowPagination]="false"
          [nzFrontPagination]="false"
          [nzVirtualItemSize]="virtual() ? rowHeight : 0"
          [nzVirtualForTrackBy]="trackById"
          [nzScroll]="virtual() ? { y: '64vh' } : {}"
          nzSize="small"
        >
          <thead>
            <tr>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="at"
                nzWidth="186px"
                i18n="@@admin.audit.col.time"
              >
                Time
              </th>
              <th nzWidth="110px" i18n="@@admin.audit.col.result">Result</th>
              <th i18n="@@admin.audit.col.operation">Operation</th>
              <th nzWidth="190px" i18n="@@admin.audit.col.actor">Actor</th>
              <th i18n="@@admin.audit.col.entity">Entity</th>
              <th nzWidth="130px" i18n="@@admin.audit.col.sourceIp">Source IP</th>
            </tr>
          </thead>
          <tbody>
            @if (virtual()) {
              <ng-template nz-virtual-scroll let-e let-i="index">
                <ng-container *ngTemplateOutlet="row; context: { $implicit: e, i: i }" />
              </ng-template>
            } @else {
              @for (e of t.data; track e.id; let i = $index) {
                <ng-container *ngTemplateOutlet="row; context: { $implicit: e, i: i }" />
              }
            }
          </tbody>
        </nz-table>
        <ng-template #row let-e let-i="i">
          <tr
            class="clickable-row"
            [class.active]="nav.cursor() === i || panel.value() === e.id"
            [attr.data-nav-index]="i"
            tabindex="0"
            [attr.aria-label]="rowLabel(e)"
            (click)="openEntry(e)"
            (keydown.enter)="openEntry(e)"
          >
            <td class="time" [attr.title]="abs(e.eventTime)">
              {{ e.eventTime | date: text.rowTimeFormat }}
            </td>
            <td>
              <app-state-chip size="sm" [tone]="result(e).holder" [label]="result(e).label" />
            </td>
            <td>
              <span class="op">{{ opLabel(e.operation) }}</span>
              <span class="op-code">{{ e.operation }}</span>
            </td>
            <td class="actor"><app-actor [actor]="e.actor" /></td>
            <td class="entity">{{ e.entityType }}{{ e.entityId ? ' / ' + e.entityId : '' }}</td>
            <td class="mono ip">{{ e.sourceIp || '-' }}</td>
          </tr>
        </ng-template>
        <div class="more" role="status">
          @if (cursor()) {
            <span class="muted">{{ loadedLabel() }}</span>
            <button
              nz-button
              type="button"
              [nzLoading]="loading()"
              (click)="loadMore()"
              i18n="@@admin.audit.loadMore"
            >
              Load more entries
            </button>
          } @else {
            <span class="muted">{{ endLabel() }}</span>
          }
        </div>
      }
    </div>

    <app-audit-event-panel
      [entry]="selected()"
      (closed)="panel.close()"
      (correlate)="byCorrelation($event)"
      (filter)="filterBy($event)"
    />
  `,
  styles: [
    `
      .op-opt {
        display: flex;
        justify-content: space-between;
        gap: 8px;
      }
      .op {
        font-weight: 500;
      }
      .op-code {
        margin-left: 8px;
        font-family: var(--bo-font-mono);
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .time {
        white-space: nowrap;
        font-variant-numeric: tabular-nums;
        color: var(--bo-ink-2);
      }
      .actor {
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        max-width: 190px;
      }
      .entity {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        color: var(--bo-ink-2);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        max-width: 360px;
      }
      .ip {
        color: var(--bo-ink-2);
      }
      .more {
        display: flex;
        justify-content: center;
        align-items: center;
        gap: 12px;
        padding: 12px;
        font-size: 12px;
      }
      tr.active > td {
        background: var(--bo-accent-soft) !important;
      }
      /* meta text keeps 4.5:1 on the selection tint */
      tr.active .op-code {
        color: var(--bo-ink-2);
      }
      tr:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
    `,
  ],
})
export class AuditPageComponent {
  private readonly api = inject(AdminApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly f = urlFilters({
    q: 'string',
    operation: 'string',
    result: 'string',
    actorId: 'string',
    projectId: 'string',
    entityType: 'string',
    correlationId: 'string',
    from: 'string',
    to: 'string',
  });
  readonly panel = panelParam('event');
  /** `?sort=at.asc` lists the oldest entries first; "Load more" continues in that direction. */
  readonly sort = serverSort(['at']);

  readonly groups = operationGroups();
  readonly results = AUDIT_RESULTS;
  readonly rowHeight = ROW_HEIGHT;
  readonly entries = signal<AuditEntry[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly projects = signal<Project[]>([]);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  private request = 0;

  readonly filters = computed(() => auditFiltersFromUrl(this.f.values()));
  private readonly queryKey = computed(() =>
    JSON.stringify([toAuditQuery(this.filters()), this.sort.query()]),
  );
  readonly range = computed(() => this.filters().range);
  readonly virtual = computed(() => this.entries().length > VIRTUAL_FROM);
  readonly selected = computed(
    () => this.entries().find((e) => e.id === this.panel.value()) ?? null,
  );
  readonly knownActors = computed<PrincipalOption[]>(() => {
    const byId = new Map<string, PrincipalOption>();
    for (const e of this.entries())
      if (e.actor?.userId && !byId.has(e.actor.userId))
        byId.set(e.actor.userId, { id: e.actor.userId, label: actorName(e.actor) });
    return [...byId.values()];
  });

  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    if (v.operation) {
      const operation = operationLabel(v.operation);
      out.push({
        id: 'operation',
        label: $localize`:@@admin.audit.chip.operation:Operation: ${operation}:operation:`,
      });
    }
    if (v.result) {
      const result = resultBadge(v.result).label;
      out.push({
        id: 'result',
        label: $localize`:@@admin.audit.chip.result:Result: ${result}:result:`,
      });
    }
    if (v.actorId) {
      const actor =
        this.knownActors().find((a) => a.id === v.actorId)?.label ?? v.actorId.slice(0, 8);
      out.push({
        id: 'actorId',
        label: $localize`:@@admin.audit.chip.actor:Actor: ${actor}:actor:`,
      });
    }
    if (v.projectId) {
      const project =
        this.projects().find((p) => p.id === v.projectId)?.key ??
        $localize`:@@admin.audit.chip.projectSelected:selected`;
      out.push({
        id: 'projectId',
        label: $localize`:@@admin.audit.chip.project:Project: ${project}:project:`,
      });
    }
    if (v.from && v.to) {
      const from = abs(v.from);
      const to = abs(v.to);
      out.push({
        id: 'range',
        label: $localize`:@@admin.audit.chip.range:Time: ${from}:from: to ${to}:to:`,
      });
    }
    if (v.entityType) {
      const entityType = v.entityType;
      out.push({
        id: 'entityType',
        label: $localize`:@@admin.audit.chip.entityType:Entity type: ${entityType}:entityType:`,
      });
    }
    if (v.correlationId) {
      const request = v.correlationId.slice(0, 12);
      out.push({
        id: 'correlationId',
        label: $localize`:@@admin.audit.chip.request:Request: ${request}:request:`,
      });
    }
    return out;
  });

  readonly presets = computed<FilterPreset[]>(() => {
    const v = this.f.values();
    return AUDIT_PRESETS.map((p) => ({ label: p.label, value: p, active: presetActive(p, v) }));
  });

  readonly nav = listNavigation({
    items: this.entries,
    open: (e) => this.openEntry(e),
    group: $localize`:@@admin.audit.shortcut.group:Audit log`,
    when: () => !this.panel.value(),
  });

  readonly text = {
    rangePlaceholder: [
      $localize`:@@admin.audit.filter.from:From`,
      $localize`:@@admin.audit.filter.to:To`,
    ],
    emptyFiltered: $localize`:@@admin.audit.empty.filtered:No audit entries match these filters.`,
    empty: $localize`:@@admin.audit.empty.none:No audit entries yet.`,
    clearFilters: $localize`:@@admin.audit.empty.clearFilters:Clear filters`,
    /** Date pattern of the table rows (Polish puts the day first). */
    rowTimeFormat: $localize`:@@admin.audit.rowTimeFormat:MMM d, HH:mm:ss`,
  };

  readonly loadedLabel = computed(() =>
    icu(
      $localize`:@@admin.audit.loaded:{count, plural, one {# entry loaded} other {# entries loaded}}`,
      { count: this.entries().length },
    ),
  );
  readonly endLabel = computed(() =>
    icu(
      $localize`:@@admin.audit.end:End of the log · {count, plural, one {# entry} other {# entries}}`,
      { count: this.entries().length },
    ),
  );

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@admin.audit.breadcrumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@admin.audit.breadcrumb.audit:Audit log` },
    ]);
    this.projectsApi
      .list()
      .subscribe({ next: (p) => this.projects.set(p), error: () => undefined });
    effect(() => {
      this.queryKey();
      untracked(() => this.search());
    });
    inject(ShortcutsService).register(
      {
        keys: 'f',
        description: $localize`:@@admin.audit.shortcut.search:Search by entity id`,
        group: $localize`:@@admin.audit.shortcut.group:Audit log`,
        handler: () => this.bar()?.focus(),
      },
      inject(DestroyRef),
    );
  }

  readonly opLabel = operationLabel;
  readonly result = (e: AuditEntry) => resultBadge(e.result);
  readonly resultWord = (r: string) => {
    const w = resultBadge(r).label;
    return w[0].toUpperCase() + w.slice(1);
  };
  readonly abs = abs;
  readonly ago = timeAgo;
  readonly trackById = (_: number, e: AuditEntry) => e.id;
  /** The operation search matches the label and the code ("LOGIN" finds "Sign-in failed"). */
  readonly matchOperation = (input: string, option: NzSelectItemInterface): boolean => {
    const q = input.trim().toLowerCase();
    return (
      String(option.nzLabel ?? '')
        .toLowerCase()
        .includes(q) ||
      String(option.nzValue ?? '')
        .toLowerCase()
        .includes(q.replace(/\s+/g, '_'))
    );
  };

  rowLabel(e: AuditEntry): string {
    const operation = operationLabel(e.operation);
    const result = resultBadge(e.result).label;
    const actor = actorName(e.actor);
    const time = abs(e.eventTime);
    return $localize`:@@admin.audit.rowLabel:${operation}:operation:, ${result}:result:, by ${actor}:actor:, ${time}:time:`;
  }

  openEntry(e: AuditEntry): void {
    if (e.id) this.panel.open(e.id);
  }

  setRange(range: Date[] | null): void {
    if (range?.length === 2)
      this.f.set({ from: range[0].toISOString(), to: range[1].toISOString() });
    else this.f.set({ from: null, to: null });
  }

  removeChip(id: string): void {
    if (id === 'range') this.f.set({ from: null, to: null });
    else this.f.set({ [id]: null });
  }

  applyPreset(p: (typeof AUDIT_PRESETS)[number]): void {
    if (presetActive(p, this.f.values()))
      this.f.set(Object.fromEntries(Object.keys(p.value).map((k) => [k, null])));
    else this.f.set(p.value as Record<string, string | null>);
  }

  /** Every entry of one request: other filters and the panel go (one navigation). */
  byCorrelation(id: string): void {
    const cleared = Object.fromEntries(Object.keys(this.f.values()).map((k) => [k, null]));
    this.navigate({ ...cleared, correlationId: id, event: null });
  }

  filterBy(patch: { actorId: string }): void {
    this.navigate({ ...patch, event: null });
  }

  private navigate(queryParams: Record<string, string | null>): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  search(): void {
    this.entries.set([]);
    this.cursor.set(null);
    this.nav.set(-1);
    this.fetch(null);
  }

  loadMore(): void {
    this.fetch(this.cursor());
  }

  private fetch(cursor: string | null): void {
    const id = ++this.request;
    this.loading.set(true);
    const query = {
      ...toAuditQuery(this.filters(), cursor),
      sort: this.sort.query() ?? undefined,
    };
    this.api.audit(query).subscribe({
      next: (page) => {
        if (id !== this.request) return;
        this.entries.update((list) => [...list, ...(page.content ?? [])]);
        this.cursor.set(page.nextCursor ?? null);
        this.loading.set(false);
      },
      error: () => {
        if (id === this.request) this.loading.set(false);
      },
    });
  }
}

function abs(iso: string | null | undefined): string {
  return absoluteTime(iso);
}

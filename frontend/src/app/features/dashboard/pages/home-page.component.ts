import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { catchError, debounceTime, of } from 'rxjs';
import { DashboardApi } from '../../../core/api/dashboard.service';
import { icu } from '../../../core/i18n/icu';
import { intlLocale } from '../../../core/i18n/locale';
import { RunsApi } from '../../../core/api/runs.service';
import type { ApprovalRequest, Dashboard, WorkflowRunSummary } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { EventStreamService, RUN_EVENT_TYPES } from '../../../core/events/event-stream.service';
import { InboxService } from '../../../core/state/inbox.service';
import { TurnQueueService } from '../../../core/state/turn-queue.service';
import { durationBetween, formatDuration } from '../../../core/ui/pipes/duration.pipe';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { batonOf, type Baton } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { absoluteTime, firstName, formatCost, sumMoney } from '../../../core/ui/relay/format';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { RunTrackComponent } from '../../../core/ui/relay/run-track.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import type { TurnItem } from '../../../core/ui/relay/turn';
import { TurnCardComponent } from '../../../core/ui/relay/turn-card.component';
import { ApprovalDetailDrawerComponent } from '../../inbox/public-api';
import { CostSparklineComponent } from '../components/cost-sparkline.component';
import { FirstRunChecklistComponent } from '../components/first-run-checklist.component';

interface FlightGroup {
  id: 'agent' | 'ci' | 'people' | 'queued';
  label: string;
  runs: Array<{ run: WorkflowRunSummary; baton: Baton }>;
}

const MAX_TURNS = 6;

/**
 * Home: your turn first. Greeting, the *Your turn* queue (turn cards), what is in flight (runs
 * held by agents or CI, with mini relay tracks), what landed today, the week's cost for people who
 * see costs, and the first-run checklist for administrators.
 */
@Component({
  selector: 'app-home-page',
  imports: [
    RouterLink,
    NzButtonModule,
    BatonComponent,
    CostComponent,
    EmptyComponent,
    GlyphComponent,
    HelpLinkComponent,
    RunTrackComponent,
    SkeletonComponent,
    TurnCardComponent,
    ApprovalDetailDrawerComponent,
    CostSparklineComponent,
    FirstRunChecklistComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page home">
      <header class="greeting">
        <div class="greet-row">
          <h1>{{ greeting() }}</h1>
          <bo-help-link topic="home" />
        </div>
        <p class="date">{{ today }}</p>
      </header>

      <div class="grid">
        <div class="main">
          <section aria-labelledby="h-turn">
            <h2 class="section-title" id="h-turn">
              <bo-glyph holder="you" [size]="14" />
              <span i18n="@@dashboard.turn.title">Your turn</span>
              @if (turns().length) {
                <span class="count">{{ turns().length }}</span>
              }
              @if (turns().length > maxTurns) {
                <a class="more" routerLink="/inbox" i18n="@@dashboard.turn.all"
                  >All {{ turns().length }} in Your turn</a
                >
              }
            </h2>
            @if (!queue.loaded() && !turns().length) {
              <bo-skeleton variant="cards" [rows]="2" />
            } @else if (!turns().length) {
              <bo-empty
                tone="calm"
                title="Nothing needs you right now."
                i18n-title="@@dashboard.turn.empty"
                [detail]="calmDetail()"
              />
            } @else {
              <ol class="turns">
                @for (t of shownTurns(); track t.id; let i = $index) {
                  <li [attr.data-nav-index]="i" tabindex="-1">
                    <bo-turn-card
                      [item]="t"
                      [active]="nav.cursor() === i"
                      (open)="open(t, i)"
                      (primary)="act(t, i)"
                    />
                  </li>
                }
              </ol>
            }
          </section>

          <section aria-labelledby="h-flight">
            <h2 class="section-title" id="h-flight">
              <span i18n="@@dashboard.flight.title">In flight</span>
              @if (flightCount()) {
                <span class="count">{{ flightCount() }}</span>
              }
              <a
                class="more"
                routerLink="/runs"
                [queryParams]="{ holder: 'agent,ci' }"
                i18n="@@dashboard.flight.all"
                >All runs in flight</a
              >
            </h2>
            @if (flightLoading() && !flight().length) {
              <bo-skeleton variant="list" [rows]="4" />
            } @else if (!flightCount()) {
              <bo-empty
                tone="quiet"
                title="No runs in flight."
                i18n-title="@@dashboard.flight.empty"
                detail="Start a run from a project workflow or from the command palette (Start run…)."
                i18n-detail="@@dashboard.flight.emptyDetail"
              />
            } @else {
              @for (g of flightGroups(); track g.id) {
                <div class="flight-group">
                  <h3>
                    {{ g.label }} <span class="count">{{ g.runs.length }}</span>
                  </h3>
                  <ul class="flight">
                    @for (f of g.runs; track f.run.id) {
                      <li>
                        <a class="flight-row" [routerLink]="['/runs', f.run.id]">
                          <bo-baton [baton]="f.baton" size="sm" appearance="plain" />
                          <span class="f-title">
                            <span class="f-name">{{ f.run.issueKey || f.run.workflowName }}</span>
                            <span class="f-sub">
                              <span class="mono">{{ f.run.projectKey }}</span>
                              @if (f.run.issueKey) {
                                &nbsp;· {{ f.run.workflowName }}
                              }
                            </span>
                          </span>
                          <bo-run-track class="f-track" [run]="f.run" />
                          <span class="f-meta">
                            <bo-cost [value]="f.run.cost" />
                            <span [attr.title]="abs(f.run.startedAt || f.run.createdAt)">{{
                              ago(f.run.startedAt || f.run.createdAt)
                            }}</span>
                          </span>
                        </a>
                      </li>
                    }
                  </ul>
                </div>
              }
            }
          </section>
        </div>

        <aside class="side">
          <app-first-run-checklist />

          <section class="panel" aria-labelledby="h-landed">
            <h2 class="section-title" id="h-landed">
              <span i18n="@@dashboard.landed.title">Landed today</span>
              @if (landed().length) {
                <span class="count">{{ landed().length }}</span>
              }
            </h2>
            @if (!landed().length) {
              <p class="muted small" i18n="@@dashboard.landed.empty">Nothing finished yet today.</p>
            } @else {
              <ul class="landed">
                @for (r of landed(); track r.id) {
                  <li>
                    <a [routerLink]="['/runs', r.id]">
                      <bo-glyph holder="done" [size]="12" />
                      <span class="l-name">{{ r.issueKey || r.workflowName }}</span>
                      <span class="l-meta">
                        <span class="mono">{{ r.projectKey }}</span> · {{ took(r) }}
                      </span>
                      <bo-cost [value]="r.cost" />
                    </a>
                  </li>
                }
              </ul>
            }
          </section>

          @if (showsCost()) {
            <section class="panel" aria-labelledby="h-cost">
              <h2 class="section-title" id="h-cost">
                <span i18n="@@dashboard.cost.title">Cost this week</span>
                <a class="more" routerLink="/costs" i18n="@@dashboard.cost.details">Details</a>
              </h2>
              <div class="cost-total">{{ weekTotal() }}</div>
              <div class="muted small">{{ monthText() }}</div>
              @if (week().length) {
                @defer (on viewport) {
                  <app-cost-sparkline [points]="week()" />
                } @placeholder {
                  <div class="spark-ph"></div>
                }
              }
            </section>
          }
        </aside>
      </div>
    </div>

    <app-approval-detail-drawer
      [approval]="selected()"
      [focus]="focusChecklist() ? 'checklist' : null"
      (closed)="selected.set(null)"
      (decided)="onDecided()"
    />
  `,
  styles: [
    `
      .greeting {
        margin: 8px 0 28px;
      }
      .greet-row {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      h1 {
        margin: 0;
        font-size: var(--bo-fs-greeting);
        font-weight: 600;
        letter-spacing: -0.015em;
        line-height: 1.2;
      }
      .date {
        margin: 4px 0 0;
        color: var(--bo-ink-3);
        font-size: 14px;
      }
      .grid {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 340px;
        gap: 32px;
        align-items: start;
      }
      @media (max-width: 1200px) {
        .grid {
          grid-template-columns: minmax(0, 1fr);
        }
      }
      .main {
        display: flex;
        flex-direction: column;
        gap: 32px;
        min-width: 0;
      }
      .side {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .more {
        margin-left: auto;
        font-size: 12px;
        font-weight: 400;
      }
      .turns {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .turns li {
        outline: none;
      }
      .flight-group + .flight-group {
        margin-top: 16px;
      }
      .flight-group h3 {
        margin: 0 0 6px;
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-3);
      }
      .flight-group h3 .count {
        font-family: var(--bo-font-mono);
      }
      .flight {
        list-style: none;
        margin: 0;
        padding: 0;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
      }
      .flight li + li {
        border-top: 1px solid var(--bo-line);
      }
      .flight-row {
        display: grid;
        grid-template-columns: 150px minmax(0, 1.2fr) minmax(120px, 1fr) auto;
        align-items: center;
        gap: 16px;
        padding: 8px 14px;
        color: inherit;
        text-decoration: none;
        min-height: calc(var(--bo-row-h) + 12px);
      }
      .flight-row:hover {
        background: var(--bo-surface-2);
      }
      .flight-row:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .f-title {
        display: flex;
        flex-direction: column;
        min-width: 0;
      }
      .f-name {
        font-weight: 500;
        font-size: 13px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .f-sub {
        font-size: 12px;
        color: var(--bo-ink-3);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .f-meta {
        display: flex;
        gap: 12px;
        align-items: center;
        font-size: 12px;
        color: var(--bo-ink-3);
        justify-content: flex-end;
        white-space: nowrap;
      }
      @media (max-width: 760px) {
        .flight-row {
          grid-template-columns: 1fr;
          gap: 6px;
        }
        .f-meta {
          justify-content: flex-start;
        }
      }
      .panel {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        padding: 14px 16px;
      }
      .panel .section-title {
        font-size: 14px;
        margin-bottom: 8px;
      }
      .landed {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .landed a {
        display: grid;
        grid-template-columns: 14px minmax(0, 1fr) auto;
        grid-template-areas: 'g name cost' 'g meta cost';
        column-gap: 8px;
        padding: 6px 0;
        color: inherit;
        text-decoration: none;
        border-top: 1px solid var(--bo-line);
      }
      .landed li:first-child a {
        border-top: 0;
      }
      .landed bo-glyph {
        grid-area: g;
        margin-top: 3px;
      }
      .landed bo-cost {
        grid-area: cost;
        align-self: center;
      }
      .l-name {
        grid-area: name;
        font-size: 13px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .landed a:hover .l-name {
        text-decoration: underline;
      }
      .l-meta {
        grid-area: meta;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .cost-total {
        font-family: var(--bo-font-mono);
        font-size: 22px;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
      }
      .small {
        font-size: 12px;
      }
      .spark-ph {
        height: 56px;
      }
    `,
  ],
})
export class HomePageComponent {
  private readonly identity = inject(IdentityService);
  private readonly runsApi = inject(RunsApi);
  private readonly dashboardApi = inject(DashboardApi);
  private readonly inbox = inject(InboxService);
  private readonly router = inject(Router);
  readonly queue = inject(TurnQueueService);

  readonly maxTurns = MAX_TURNS;
  readonly today = new Date().toLocaleDateString(intlLocale(), {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
  readonly flight = signal<WorkflowRunSummary[]>([]);
  readonly flightLoading = signal(true);
  readonly finished = signal<WorkflowRunSummary[]>([]);
  readonly dashboard = signal<Dashboard | null>(null);
  readonly selected = signal<ApprovalRequest | null>(null);
  readonly focusChecklist = signal(false);

  readonly greeting = computed(() => {
    const u = this.identity.user();
    const name = firstName(u?.displayName, u?.email);
    const h = new Date().getHours();
    const part = h < 5 || h >= 18 ? 'evening' : h < 12 ? 'morning' : 'afternoon';
    if (!name)
      return part === 'morning'
        ? $localize`:@@dashboard.greeting.morning:Good morning`
        : part === 'afternoon'
          ? $localize`:@@dashboard.greeting.afternoon:Good afternoon`
          : $localize`:@@dashboard.greeting.evening:Good evening`;
    return part === 'morning'
      ? $localize`:@@dashboard.greeting.morningName:Good morning, ${name}:name:`
      : part === 'afternoon'
        ? $localize`:@@dashboard.greeting.afternoonName:Good afternoon, ${name}:name:`
        : $localize`:@@dashboard.greeting.eveningName:Good evening, ${name}:name:`;
  });

  readonly turns = this.queue.items;
  readonly shownTurns = computed(() => this.turns().slice(0, MAX_TURNS));

  /** Runs held by agents or CI (and gates of other people); gates you can decide are above. */
  readonly flightGroups = computed<FlightGroup[]>(() => {
    const mine = new Set(this.inbox.decidable().map((a) => a.id));
    const groups: Record<FlightGroup['id'], FlightGroup> = {
      agent: { id: 'agent', label: $localize`:@@dashboard.flight.agents:Agents working`, runs: [] },
      ci: { id: 'ci', label: $localize`:@@dashboard.flight.ci:Waiting for CI`, runs: [] },
      people: {
        id: 'people',
        label: $localize`:@@dashboard.flight.people:With other approvers`,
        runs: [],
      },
      queued: { id: 'queued', label: $localize`:@@dashboard.flight.queued:Queued`, runs: [] },
    };
    for (const run of this.flight()) {
      const gate =
        run.waitingOn?.kind === 'HUMAN_GATE' || run.waitingOn?.kind === 'WRITE_CONFIRMATION';
      if (gate && run.waitingOn?.approvalId && mine.has(run.waitingOn.approvalId)) continue;
      const baton = batonOf.run(run, gate ? { canDecide: false } : {});
      const id: FlightGroup['id'] =
        baton.holder === 'agent'
          ? 'agent'
          : baton.holder === 'ci'
            ? 'ci'
            : gate
              ? 'people'
              : 'queued';
      groups[id].runs.push({ run, baton });
    }
    return Object.values(groups).filter((g) => g.runs.length);
  });
  readonly flightCount = computed(() => this.flightGroups().reduce((n, g) => n + g.runs.length, 0));

  readonly landed = computed(() => {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    return this.finished()
      .filter((r) => r.finishedAt && Date.parse(r.finishedAt) >= start.getTime())
      .slice(0, 8);
  });

  readonly showsCost = computed(
    () => this.identity.can('usage.view') || this.identity.canInProject('project.usage.view'),
  );
  readonly week = computed(() => (this.dashboard()?.costLast30DaysByDay ?? []).slice(-7));
  readonly weekTotal = computed(() => formatCost(sumMoney(this.week().map((p) => p.cost)) ?? 0));
  readonly monthText = computed(() => {
    const m = this.dashboard()?.costThisMonth;
    return m
      ? $localize`:@@dashboard.cost.thisMonth:${formatCost(m)}:cost: this month`
      : $localize`:@@dashboard.cost.noUsage:No usage recorded yet.`;
  });

  readonly calmDetail = computed(() => {
    const groups = this.flightGroups();
    const n = (id: FlightGroup['id']) => groups.find((g) => g.id === id)?.runs.length ?? 0;
    const parts = [
      n('agent')
        ? icu($localize`:@@dashboard.calm.agents:{count, plural, other {# with agents}}`, {
            count: n('agent'),
          })
        : null,
      n('ci')
        ? icu($localize`:@@dashboard.calm.ci:{count, plural, other {# waiting for CI}}`, {
            count: n('ci'),
          })
        : null,
      n('people')
        ? icu($localize`:@@dashboard.calm.people:{count, plural, other {# with other approvers}}`, {
            count: n('people'),
          })
        : null,
    ].filter(Boolean);
    return parts.length
      ? $localize`:@@dashboard.calm.inFlight:In flight: ${parts.join(', ')}:parts:.`
      : $localize`:@@dashboard.calm.nothing:Nothing is in flight either.`;
  });

  readonly nav = listNavigation({
    items: this.shownTurns,
    open: (t, i) => this.open(t, i),
    group: $localize`:@@dashboard.shortcut.group:Home`,
    when: () => !this.selected(),
  });

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@dashboard.crumb:Home` }]);
    this.queue.ensure();
    this.load();
    inject(ShortcutsService).register(
      {
        keys: 'a',
        description: $localize`:@@dashboard.shortcut.review:Review the gate under the cursor`,
        group: $localize`:@@dashboard.shortcut.reviewGroup:Home`,
        when: () => !this.selected() && this.shownTurns()[this.nav.cursor()]?.kind === 'gate',
        handler: () => this.act(this.shownTurns()[this.nav.cursor()], this.nav.cursor()),
      },
      inject(DestroyRef),
    );
    inject(EventStreamService)
      .of(...RUN_EVENT_TYPES, 'CiExecutionFinished')
      .pipe(debounceTime(1500), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => this.load());
  }

  ago = timeAgo;
  abs = absoluteTime;

  took(r: WorkflowRunSummary): string {
    return formatDuration(durationBetween(r.startedAt ?? r.createdAt, r.finishedAt));
  }

  open(t: TurnItem, index: number): void {
    this.nav.set(index);
    if (t.approval) {
      this.focusChecklist.set(false);
      this.selected.set(t.approval);
    } else void this.router.navigate(t.link as unknown[], { queryParams: t.queryParams });
  }

  /** `a` / primary button: a gate opens on its checklist; it is never approved from here. */
  act(t: TurnItem | undefined, index: number): void {
    if (!t) return;
    this.nav.set(index);
    if (t.approval) {
      this.focusChecklist.set(true);
      this.selected.set(t.approval);
    } else void this.router.navigate(t.link as unknown[], { queryParams: t.queryParams });
  }

  onDecided(): void {
    this.selected.set(null);
    this.load();
  }

  private load(): void {
    this.runsApi
      .list({ status: ['RUNNING', 'WAITING', 'PENDING'], size: 40 })
      .pipe(catchError(() => of({ content: [] })))
      .subscribe((p) => {
        this.flight.set(p.content ?? []);
        this.flightLoading.set(false);
      });
    this.runsApi
      .list({ status: ['SUCCEEDED'], size: 20 })
      .pipe(catchError(() => of({ content: [] })))
      .subscribe((p) => this.finished.set(p.content ?? []));
    if (this.showsCost())
      this.dashboardApi
        .get()
        .pipe(catchError(() => of(null)))
        .subscribe((d) => this.dashboard.set(d));
  }
}

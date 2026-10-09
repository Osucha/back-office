import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, debounceTime, forkJoin, of } from 'rxjs';
import { BudgetsApi } from '../api/budgets.service';
import { CiApi } from '../api/ci.service';
import { ConnectionRemindersApi } from '../api/connection-reminders.service';
import { RunsApi } from '../api/runs.service';
import type {
  BudgetStatus,
  CiExecutionSummary,
  Connection,
  WorkflowRunSummary,
} from '../api/types';
import { IdentityService } from '../auth/identity.service';
import { EventStreamService } from '../events/event-stream.service';
import { InboxService } from './inbox.service';
import { buildTurnQueue } from './turn-queue';

/**
 * The *Your turn* queue shared by Home and the Your turn page. Gates come from `InboxService`
 * (also behind the amber counter); failed runs and CI executions are loaded here and refreshed on
 * the SSE events that change them.
 */
@Injectable({ providedIn: 'root' })
export class TurnQueueService {
  private readonly runsApi = inject(RunsApi);
  private readonly ciApi = inject(CiApi);
  private readonly budgetsApi = inject(BudgetsApi);
  private readonly reminders = inject(ConnectionRemindersApi);
  private readonly identity = inject(IdentityService);
  private readonly inbox = inject(InboxService);
  private readonly events = inject(EventStreamService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly failedRuns = signal<WorkflowRunSummary[]>([]);
  private readonly ciExecutions = signal<CiExecutionSummary[]>([]);
  private readonly budgets = signal<BudgetStatus[]>([]);
  private readonly expiring = signal<Connection[]>([]);
  private readonly failuresLoaded = signal(false);
  private started = false;

  readonly loading = signal(false);
  readonly loaded = computed(() => this.inbox.loaded() && this.failuresLoaded());

  readonly items = computed(() => {
    const me = this.identity.me();
    const memberOf = new Set((me?.projects ?? []).map((p) => p.projectId!).filter(Boolean));
    return buildTurnQueue({
      approvals: this.inbox.open(),
      failedRuns: this.failedRuns(),
      ciExecutions: this.ciExecutions(),
      userId: me?.user?.id,
      canRetry: (projectId) => !!projectId && this.identity.canInProject('run.start', projectId),
      projectIds: memberOf.size ? memberOf : null,
      budgets: this.budgets(),
      canManageBudget: (b) =>
        b.scope === 'PLATFORM'
          ? this.identity.can('admin.settings.manage')
          : !!b.projectId && this.identity.canInProject('project.manage', b.projectId),
      expiringConnections: this.expiring(),
    });
  });
  readonly count = computed(() => this.items().length);

  constructor() {
    effect(() => {
      if (!this.identity.isAuthenticated()) {
        untracked(() => {
          this.failedRuns.set([]);
          this.ciExecutions.set([]);
          this.budgets.set([]);
          this.expiring.set([]);
          this.failuresLoaded.set(false);
          this.started = false;
        });
      }
    });
    this.events
      .of(
        'WorkflowRunFinished',
        'WorkflowRunStarted',
        'CiExecutionFinished',
        'NodeFailed',
        'ConnectionExpiring',
      )
      .pipe(debounceTime(800), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.started) this.refreshFailures();
      });
  }

  /** Starts loading (idempotent); pages call it when they show the queue. */
  ensure(): void {
    if (this.started || !this.identity.isAuthenticated()) return;
    this.started = true;
    this.refreshFailures();
  }

  refresh(): void {
    this.inbox.refresh();
    this.refreshFailures();
  }

  /** Drops an item right away (after a decision); the next load reconciles. */
  dismissRun(runId: string): void {
    this.failedRuns.update((l) => l.filter((r) => r.id !== runId));
  }

  private refreshFailures(): void {
    this.loading.set(true);
    forkJoin({
      runs: this.runsApi
        .list({ status: ['FAILED'], size: 50 })
        .pipe(catchError(() => of({ content: [] as WorkflowRunSummary[] }))),
      ci: this.ciApi
        .listExecutions({ size: 50 })
        .pipe(catchError(() => of({ content: [] as CiExecutionSummary[] }))),
      // Budgets the caller can see (a backend without budgets answers 404: no budget cards).
      budgets: this.budgetsApi.list().pipe(catchError(() => of([] as BudgetStatus[]))),
      // Tokens the caller manages that expire within 14 days (empty for everybody else).
      expiring: this.reminders.expiring().pipe(catchError(() => of([] as Connection[]))),
    }).subscribe(({ runs, ci, budgets, expiring }) => {
      this.failedRuns.set(runs.content ?? []);
      this.ciExecutions.set(ci.content ?? []);
      this.budgets.set(Array.isArray(budgets) ? budgets : []);
      this.expiring.set(Array.isArray(expiring) ? expiring : []);
      this.failuresLoaded.set(true);
      this.loading.set(false);
    });
  }
}

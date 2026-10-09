import { DestroyRef, Injectable, NgZone, effect, inject, signal, untracked } from '@angular/core';
import { Observable, Subject, filter } from 'rxjs';
import { API_BASE } from '../api/api-base';
import type { DomainEventEnvelope, DomainEventType } from '../api/types';
import { IdentityService } from '../auth/identity.service';
import { SessionExpiryService } from '../auth/session-expiry.service';

export type ConnectionState = 'disconnected' | 'connecting' | 'connected';

export const EVENT_TYPES: DomainEventType[] = [
  'WorkflowRunStarted',
  'NodeStarted',
  'NodeCompleted',
  'NodeFailed',
  'RunWaitingForHuman',
  'RunWaitingForCi',
  'ApprovalDecided',
  'ApprovalAssigned',
  'CiSignalReceived',
  'WorkflowRunFinished',
  'CiExecutionFinished',
  'RunnerLog',
  'BenchmarkProgress',
  'BenchmarkFinished',
  // llm-gateway budgets (payload scope, projectId, period, spentUsd, limitUsd, alertAtPercent / hardStop)
  'BudgetAlert',
  'BudgetExceeded',
  // daily connection token reminder (payload connectionId, projectId | null, name, scope, expiresAt, daysLeft)
  'ConnectionExpiring',
  // ADR 0038: a personal intake rule found new issues (payload ownerUserId, ruleId, ruleName, count, issueKeys)
  'IntakeProposalsFound',
];

/** Event types that change the state of a workflow run (used to refresh run lists/details). */
export const RUN_EVENT_TYPES: DomainEventType[] = [
  'WorkflowRunStarted',
  'NodeStarted',
  'NodeCompleted',
  'NodeFailed',
  'RunWaitingForHuman',
  'RunWaitingForCi',
  'ApprovalDecided',
  'CiSignalReceived',
  'WorkflowRunFinished',
];

/** Every event type of the contract (`DomainEventEnvelope.type`). */
export type LiveEventType = DomainEventType;

export const MAX_BACKOFF_MS = 30_000;

/**
 * Live updates over Server-Sent Events (`GET /api/v1/events`).
 *
 * A plain same-origin `EventSource`: the browser sends the `BO_SESSION` cookie, no token in the
 * URL. Connected only while the user is signed in and the session has not expired; reconnects with
 * exponential backoff (1 s to 30 s) and resumes from the last event id (`lastEventId` query
 * parameter, same semantics as `Last-Event-ID`). A failing stream never raises the session notice
 * itself (`EventSource` hides the status code); the next REST call does, and then the stream stops.
 */
@Injectable({ providedIn: 'root' })
export class EventStreamService {
  private readonly identity = inject(IdentityService);
  private readonly session = inject(SessionExpiryService);
  private readonly zone = inject(NgZone);
  private readonly destroyRef = inject(DestroyRef);

  private source: EventSource | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private backoffMs = 1000;
  private lastEventId: string | null = null;

  private readonly events$ = new Subject<DomainEventEnvelope>();

  /** Latest event received (any type). */
  readonly latest = signal<DomainEventEnvelope | null>(null);
  readonly state = signal<ConnectionState>('disconnected');
  /** Number of events received since the page loaded. */
  readonly received = signal(0);

  constructor() {
    effect(() => {
      const active = this.canConnect();
      untracked(() => {
        this.disconnect();
        if (active) this.connect();
      });
    });
    this.destroyRef.onDestroy(() => this.disconnect());
  }

  /** All events. */
  all(): Observable<DomainEventEnvelope> {
    return this.events$.asObservable();
  }

  /** Events of the given type(s). */
  of(...types: LiveEventType[]): Observable<DomainEventEnvelope> {
    const set = new Set<string>(types);
    return this.events$.pipe(filter((e) => set.has(e.type)));
  }

  /** Events whose payload references the given run id. */
  forRun(runId: string): Observable<DomainEventEnvelope> {
    return this.events$.pipe(filter((e) => payloadId(e, 'runId') === runId));
  }

  /** Same as `forRun` but the id is read lazily (route inputs that change over time). */
  forRun$(runId: () => string): Observable<DomainEventEnvelope> {
    return this.events$.pipe(filter((e) => payloadId(e, 'runId') === runId()));
  }

  /** Events whose payload references the given benchmark id. */
  forBenchmark(benchmarkId: string): Observable<DomainEventEnvelope> {
    return this.events$.pipe(filter((e) => payloadId(e, 'benchmarkId') === benchmarkId));
  }

  private canConnect(): boolean {
    return this.identity.isAuthenticated() && !this.session.expired();
  }

  private connect(): void {
    if (typeof EventSource === 'undefined') return;
    this.state.set('connecting');
    const qs = this.lastEventId
      ? `?${new URLSearchParams({ lastEventId: this.lastEventId }).toString()}`
      : '';
    const source = new EventSource(`${API_BASE}/events${qs}`);
    this.source = source;

    source.onopen = () => {
      this.zone.run(() => {
        this.backoffMs = 1000;
        this.state.set('connected');
      });
    };
    source.onerror = () => {
      this.zone.run(() => {
        this.state.set('disconnected');
        this.scheduleReconnect();
      });
    };
    const handler = (ev: MessageEvent<string>) => this.zone.run(() => this.handle(ev));
    // The backend sets `event:` to the domain event type; also listen to unnamed messages.
    for (const type of EVENT_TYPES) source.addEventListener(type, handler as EventListener);
    source.onmessage = handler;
  }

  private handle(ev: MessageEvent<string>): void {
    if (!ev.data) return;
    let envelope: DomainEventEnvelope;
    try {
      envelope = JSON.parse(ev.data) as DomainEventEnvelope;
    } catch {
      return;
    }
    if (!envelope.type && ev.type && ev.type !== 'message') {
      envelope.type = ev.type as DomainEventType;
    }
    if (ev.lastEventId) this.lastEventId = ev.lastEventId;
    else if (envelope.id) this.lastEventId = envelope.id;
    this.latest.set(envelope);
    this.received.update((n) => n + 1);
    this.events$.next(envelope);
  }

  private scheduleReconnect(): void {
    this.closeSource();
    if (this.reconnectTimer || !this.canConnect()) return;
    const delay = this.backoffMs;
    this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.canConnect()) this.connect();
    }, delay);
  }

  private disconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.closeSource();
    this.state.set('disconnected');
  }

  private closeSource(): void {
    if (this.source) {
      this.source.close();
      this.source = null;
    }
  }
}

function payloadId(e: DomainEventEnvelope, field: string): string | undefined {
  const value = (e.payload as Record<string, unknown> | undefined)?.[field];
  return typeof value === 'string' ? value : undefined;
}

import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { DomainEventEnvelope } from '../api/types';
import { EventStreamService } from '../events/event-stream.service';
import type { BatonHolder } from '../ui/relay/baton';

export interface ActivityEntry {
  id: string;
  at: string;
  holder: BatonHolder;
  text: string;
  detail: string | null;
  projectId: string | null;
  projectKey: string | null;
  link: unknown[] | null;
}

const MAX = 150;

/** Human sentence and holder for one live event (the activity dock). `null` = not shown. */
export function describeEvent(e: DomainEventEnvelope): Omit<ActivityEntry, 'id' | 'at'> | null {
  const p = (e.payload ?? {}) as Record<string, unknown>;
  const str = (k: string) => (typeof p[k] === 'string' ? (p[k] as string) : null);
  const runId = str('runId');
  const node = str('nodeName') ?? str('nodeId');
  const base = {
    projectId: str('projectId'),
    projectKey: str('projectKey'),
    link: runId ? ['/runs', runId] : null,
  };
  const subject = str('issueKey') ?? str('workflowName');
  switch (e.type) {
    case 'WorkflowRunStarted':
      return {
        ...base,
        holder: 'agent',
        text: $localize`:@@core.activity.runStarted:Run started`,
        detail: subject,
      };
    case 'NodeStarted':
      return {
        ...base,
        holder: 'agent',
        text: $localize`:@@core.activity.stepStarted:Step started`,
        detail: node,
      };
    case 'NodeCompleted':
      return {
        ...base,
        holder: 'done',
        text: $localize`:@@core.activity.stepFinished:Step finished`,
        detail: node,
      };
    case 'NodeFailed':
      return {
        ...base,
        holder: 'blocked',
        text: $localize`:@@core.activity.stepFailed:Step failed`,
        detail: node,
      };
    case 'RunWaitingForHuman':
      return {
        ...base,
        holder: 'you',
        text: $localize`:@@core.activity.waitingForPerson:Waiting for a person`,
        detail: node,
      };
    case 'RunWaitingForCi':
      return {
        ...base,
        holder: 'ci',
        text: $localize`:@@core.activity.waitingForCi:Waiting for CI`,
        detail: str('signal') ?? node,
      };
    case 'CiSignalReceived':
      return {
        ...base,
        holder: 'ci',
        text: $localize`:@@core.activity.ciSignal:CI signal received`,
        detail: str('signal'),
      };
    case 'ApprovalDecided':
      return {
        ...base,
        holder: 'done',
        text: $localize`:@@core.activity.gateDecided:Gate decided`,
        detail: str('option'),
      };
    case 'ApprovalAssigned':
      return {
        ...base,
        holder: 'you',
        text: $localize`:@@core.activity.gateAssigned:Gate assigned`,
        detail: null,
      };
    case 'WorkflowRunFinished':
      return { ...base, ...runFinished(str), detail: subject };
    case 'CiExecutionFinished':
      return ciExecutionFinished(str, base);
    case 'BenchmarkFinished': {
      const id = str('benchmarkId');
      return {
        ...base,
        link: id ? ['/evaluation/benchmarks', id] : null,
        holder: 'done',
        text: $localize`:@@core.activity.benchmarkFinished:Benchmark finished`,
        detail: str('name'),
      };
    }
    default:
      return null;
  }
}

type Described = Omit<ActivityEntry, 'id' | 'at'>;
type PayloadString = (key: string) => string | null;

function runFinished(str: PayloadString): Pick<Described, 'holder' | 'text'> {
  if (str('status') === 'FAILED' || str('outcome') === 'FAILURE')
    return { holder: 'blocked', text: $localize`:@@core.activity.runFailed:Run failed` };
  if (str('status') === 'CANCELLED')
    return { holder: 'idle', text: $localize`:@@core.activity.runCancelled:Run cancelled` };
  return { holder: 'done', text: $localize`:@@core.activity.runFinished:Run finished` };
}

function ciExecutionFinished(
  str: PayloadString,
  base: Pick<Described, 'projectId' | 'projectKey' | 'link'>,
): Described {
  const id = str('ciExecutionId') ?? str('executionId');
  const failed = str('gateResult') === 'FAIL' || str('status') === 'FAILED';
  return {
    ...base,
    link: id ? ['/ci', id] : base.link,
    holder: failed ? 'blocked' : 'done',
    text: failed
      ? $localize`:@@core.activity.ciFailed:CI review failed`
      : $localize`:@@core.activity.ciPassed:CI review passed`,
    detail: str('entrypoint'),
  };
}

/** The last live events, newest first, for the activity dock (collected while signed in). */
@Injectable({ providedIn: 'root' })
export class ActivityFeedService {
  readonly entries = signal<ActivityEntry[]>([]);

  constructor() {
    inject(EventStreamService)
      .all()
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((e) => {
        const d = describeEvent(e);
        if (!d) return;
        const entry: ActivityEntry = {
          ...d,
          id: e.id ?? `${e.type}:${e.occurredAt}`,
          at: e.occurredAt,
        };
        this.entries.update((list) =>
          [entry, ...list.filter((x) => x.id !== entry.id)].slice(0, MAX),
        );
      });
  }

  clear(): void {
    this.entries.set([]);
  }
}

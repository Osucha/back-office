import { Injectable, inject, signal, type Signal } from '@angular/core';
import { finalize } from 'rxjs';
import { RunsApi } from '../api/runs.service';
import type { WorkflowRun, WorkflowRunSummary } from '../api/types';

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
const MAX_CONCURRENT = 4;
const MAX_ENTRIES = 300;

interface Entry {
  version: string;
  value: ReturnType<typeof signal<WorkflowRun | null>>;
  loading: boolean;
}

/**
 * Full runs (`GET /runs/{id}`) for list rows that show a relay track: run summaries have no node
 * executions. Loads lazily (only for rows that render), at most four at a time, and caches by a
 * version key (status, current node, finish time) so a row reloads only when the run moved on.
 * Finished runs are cached for the session.
 */
@Injectable({ providedIn: 'root' })
export class RunDetailsStore {
  private readonly api = inject(RunsApi);
  private readonly entries = new Map<string, Entry>();
  private readonly queue: string[] = [];
  private active = 0;

  /** The full run for a summary; `null` until loaded. */
  get(
    summary: Pick<WorkflowRunSummary, 'id' | 'status' | 'currentNodeId' | 'finishedAt'>,
  ): Signal<WorkflowRun | null> {
    const id = summary.id!;
    const version = versionOf(summary);
    let entry = this.entries.get(id);
    if (!entry) {
      entry = { version: '', value: signal<WorkflowRun | null>(null), loading: false };
      this.entries.set(id, entry);
      this.trim();
    }
    if (entry.version !== version && !entry.loading) {
      entry.version = version;
      this.enqueue(id);
    }
    return entry.value.asReadonly();
  }

  /** Seeds the cache with a run already loaded elsewhere (run page, home). */
  put(run: WorkflowRun): void {
    if (!run.id) return;
    const entry = this.entries.get(run.id) ?? {
      version: '',
      value: signal<WorkflowRun | null>(null),
      loading: false,
    };
    entry.version = versionOf(run);
    entry.value.set(run);
    this.entries.set(run.id, entry);
  }

  private enqueue(id: string): void {
    if (!this.queue.includes(id)) this.queue.push(id);
    this.pump();
  }

  private pump(): void {
    while (this.active < MAX_CONCURRENT && this.queue.length) {
      const id = this.queue.shift()!;
      const entry = this.entries.get(id);
      if (!entry) continue;
      entry.loading = true;
      this.active++;
      this.api
        .get(id)
        .pipe(finalize(() => this.done(entry)))
        .subscribe({
          next: (run) => {
            entry.value.set(run);
            entry.version = versionOf(run);
          },
          error: () => undefined,
        });
    }
  }

  private done(entry: Entry): void {
    entry.loading = false;
    this.active--;
    this.pump();
  }

  private trim(): void {
    if (this.entries.size <= MAX_ENTRIES) return;
    for (const [id, e] of this.entries) {
      if (this.entries.size <= MAX_ENTRIES * 0.8) break;
      const s = e.value()?.status;
      if (!e.loading && s && TERMINAL.has(s)) this.entries.delete(id);
    }
  }
}

function versionOf(r: Pick<WorkflowRunSummary, 'status' | 'currentNodeId' | 'finishedAt'>): string {
  return `${r.status}|${r.currentNodeId ?? ''}|${r.finishedAt ?? ''}`;
}

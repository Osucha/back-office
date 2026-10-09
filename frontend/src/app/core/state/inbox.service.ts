import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime } from 'rxjs';
import { ApprovalsApi } from '../api/approvals.service';
import type { ApprovalRequest } from '../api/types';
import { IdentityService } from '../auth/identity.service';
import { EventStreamService } from '../events/event-stream.service';

/**
 * Open approvals shared by the header badge, dashboard and inbox. Loaded once per signed-in user and
 * refreshed whenever an SSE event indicates that the inbox changed.
 */
@Injectable({ providedIn: 'root' })
export class InboxService {
  private readonly api = inject(ApprovalsApi);
  private readonly events = inject(EventStreamService);
  private readonly identity = inject(IdentityService);
  private readonly destroyRef = inject(DestroyRef);

  readonly open = signal<ApprovalRequest[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly count = computed(() => this.open().length);
  /** Open gates the signed-in user may decide (the amber "your turn" counter). */
  readonly decidable = computed(() => this.open().filter((a) => a.canDecide !== false));
  readonly decidableCount = computed(() => this.decidable().length);

  constructor() {
    effect(() => {
      if (this.identity.isAuthenticated()) this.refresh();
      else {
        this.open.set([]);
        this.loaded.set(false);
      }
    });
    this.events
      .of('RunWaitingForHuman', 'ApprovalDecided', 'ApprovalAssigned', 'WorkflowRunFinished')
      .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
  }

  refresh(): void {
    if (!this.identity.isAuthenticated()) return;
    this.loading.set(true);
    this.api.list('OPEN').subscribe({
      next: (list) => {
        this.open.set(list);
        this.loaded.set(true);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** Optimistically drop a decided approval, then reconcile with the server. */
  remove(approvalId: string, reconcile = true): void {
    this.open.update((list) => list.filter((a) => a.id !== approvalId));
    if (reconcile) this.refresh();
  }

  /** Puts an approval back after a failed optimistic decision. */
  restore(approval: ApprovalRequest): void {
    this.open.update((list) =>
      list.some((a) => a.id === approval.id) ? list : [approval, ...list],
    );
  }
}

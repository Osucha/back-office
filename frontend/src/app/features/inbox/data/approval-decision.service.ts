import { Injectable, inject } from '@angular/core';
import { NzMessageService } from 'ng-zorro-antd/message';
import { Observable, catchError, tap, throwError } from 'rxjs';
import { ApprovalsApi } from '../../../core/api/approvals.service';
import type { ApprovalRequest, Decision } from '../../../core/api/types';
import { InboxService } from '../../../core/state/inbox.service';

/**
 * Sends a decision and keeps the shared inbox state in sync. Optimistic: the gate leaves the
 * queue at once and comes back if the request fails. The API has no way to take a decision back,
 * so there is no undo.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalDecisionService {
  private readonly api = inject(ApprovalsApi);
  private readonly inbox = inject(InboxService);
  private readonly message = inject(NzMessageService);

  decide(approval: ApprovalRequest, decision: Decision): Observable<ApprovalRequest> {
    const id = approval.id!;
    this.inbox.remove(id, false);
    return this.api.decide(id, decision).pipe(
      tap(() => {
        this.inbox.refresh();
        const what = approval.issueKey
          ? `${approval.issueKey} (${approval.workflowName})`
          : (approval.workflowName ?? approval.nodeId ?? '');
        this.message.success(`${decidedWord(decision.option)}: ${what}`);
      }),
      catchError((err: unknown) => {
        this.inbox.restore(approval);
        return throwError(() => err);
      }),
    );
  }
}

/** Button styling per decision option; unknown options render as default buttons. */
export function optionStyle(option: string): {
  type: 'primary' | 'default' | 'dashed';
  danger: boolean;
  icon: string;
} {
  const upper = option.toUpperCase();
  if (upper === 'APPROVE' || upper === 'MERGE' || upper === 'ACCEPT' || upper === 'YES') {
    return { type: 'primary', danger: false, icon: 'check' };
  }
  if (upper === 'REJECT' || upper === 'CANCEL' || upper === 'NO' || upper === 'ABORT') {
    return { type: 'default', danger: true, icon: 'close' };
  }
  if (upper === 'REVISE' || upper === 'RETRY' || upper === 'REDO') {
    return { type: 'default', danger: false, icon: 'redo' };
  }
  return { type: 'default', danger: false, icon: 'right' };
}

/** Button label (verb + object where it helps): approve -> "Approve", revise -> "Send back for revision". */
export function optionLabel(option: string): string {
  switch (option.toUpperCase()) {
    case 'APPROVE':
      return $localize`:@@inbox.option.approve:Approve`;
    case 'REVISE':
      return $localize`:@@inbox.option.revise:Send back for revision`;
    case 'REJECT':
      return $localize`:@@inbox.option.reject:Reject`;
    case 'MERGE':
      return $localize`:@@inbox.option.merge:Approve and merge`;
    default: {
      const s = option.replace(/[_-]+/g, ' ').trim().toLowerCase();
      return s ? s[0].toUpperCase() + s.slice(1) : option;
    }
  }
}

/** Gate shortcut per option: `a` approve, `r` revise, `x` reject. */
export function optionKey(option: string): string | null {
  switch (option.toUpperCase()) {
    case 'APPROVE':
    case 'MERGE':
    case 'ACCEPT':
      return 'a';
    case 'REVISE':
    case 'REDO':
      return 'r';
    case 'REJECT':
    case 'ABORT':
      return 'x';
    default:
      return null;
  }
}

function decidedWord(option: string): string {
  switch (option.toUpperCase()) {
    case 'APPROVE':
    case 'MERGE':
      return $localize`:@@inbox.decided.approved:Approved`;
    case 'REVISE':
      return $localize`:@@inbox.decided.revised:Sent back for revision`;
    case 'REJECT':
      return $localize`:@@inbox.decided.rejected:Rejected`;
    default:
      return optionLabel(option);
  }
}

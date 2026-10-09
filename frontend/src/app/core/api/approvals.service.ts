import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from './api-base';
import type {
  ApprovalRequest,
  ApprovalStatus,
  AssignApproval,
  AssigneeCandidate,
  Decision,
  WriteIdentity,
} from './types';

@Injectable({ providedIn: 'root' })
export class ApprovalsApi {
  private readonly http = inject(HttpClient);

  list(status: ApprovalStatus = 'OPEN'): Observable<ApprovalRequest[]> {
    return this.http.get<ApprovalRequest[]>(url('approvals'), { params: params({ status }) });
  }

  get(approvalId: string): Observable<ApprovalRequest> {
    return this.http.get<ApprovalRequest>(url('approvals', approvalId));
  }

  decide(approvalId: string, decision: Decision): Observable<ApprovalRequest> {
    return this.http.post<ApprovalRequest>(url('approvals', approvalId, 'decide'), decision);
  }

  /** `run.assign`: who decides this open approval from now on (empty = back to the gate's rule). */
  assign(approvalId: string, input: AssignApproval): Observable<ApprovalRequest> {
    return this.http.put<ApprovalRequest>(
      url('approvals', approvalId, 'assignees'),
      input,
      silent(),
    );
  }

  /** ADR 0035: the identities the caller could approve this write confirmation as. */
  writeIdentity(approvalId: string): Observable<WriteIdentity> {
    return this.http.get<WriteIdentity>(url('approvals', approvalId, 'write-identity'), silent());
  }

  /** `run.assign`: project members who could decide this approval if assigned. */
  candidates(approvalId: string): Observable<AssigneeCandidate[]> {
    return this.http.get<AssigneeCandidate[]>(url('approvals', approvalId, 'assignee-candidates'));
  }
}

import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from './api-base';
import type {
  Connection,
  IntakeProposal,
  IntakeProposalState,
  IntakeRule,
  IntakeRuleInput,
} from './types';

/** Personal work (tag `my-work`, ADR 0038): the caller's personal intake rules and run proposals. */
@Injectable({ providedIn: 'root' })
export class MyWorkApi {
  private readonly http = inject(HttpClient);

  rules(): Observable<IntakeRule[]> {
    return this.http.get<IntakeRule[]>(url('me', 'intake', 'rules'));
  }

  /** 400 (validation) is shown by the caller. */
  createRule(input: IntakeRuleInput): Observable<IntakeRule> {
    return this.http.post<IntakeRule>(url('me', 'intake', 'rules'), input, silent());
  }

  /** 400 (validation) is shown by the caller. */
  updateRule(id: string, input: IntakeRuleInput): Observable<IntakeRule> {
    return this.http.put<IntakeRule>(url('me', 'intake', 'rules', id), input, silent());
  }

  deleteRule(id: string): Observable<void> {
    return this.http.delete<void>(url('me', 'intake', 'rules', id));
  }

  pollRule(id: string): Observable<IntakeRule> {
    return this.http.post<IntakeRule>(url('me', 'intake', 'rules', id, 'poll'), {});
  }

  proposals(state: IntakeProposalState = 'NEW'): Observable<IntakeProposal[]> {
    return this.http.get<IntakeProposal[]>(url('me', 'intake', 'proposals'), {
      params: params({ state }),
    });
  }

  start(id: string, workflowId?: string | null): Observable<IntakeProposal> {
    return this.http.post<IntakeProposal>(url('me', 'intake', 'proposals', id, 'start'), {
      workflowId: workflowId ?? null,
    });
  }

  dismiss(id: string): Observable<IntakeProposal> {
    return this.http.post<IntakeProposal>(url('me', 'intake', 'proposals', id, 'dismiss'), {});
  }

  restore(id: string): Observable<IntakeProposal> {
    return this.http.post<IntakeProposal>(url('me', 'intake', 'proposals', id, 'restore'), {});
  }

  /** The caller's personal Jira connections (ADR 0035), for the rule form. */
  myConnections(): Observable<Connection[]> {
    return this.http.get<Connection[]>(url('me', 'connections'));
  }
}

import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type { BudgetInput, BudgetStatus } from './types';

/** Monthly budgets (tag `budgets`, ADR 0025). */
@Injectable({ providedIn: 'root' })
export class BudgetsApi {
  private readonly http = inject(HttpClient);

  /**
   * Platform budget (for admins) and the budgets of the caller's projects with current spend.
   * Silent: it feeds ambient UI (turn cards, the Costs header) that simply stays empty on errors.
   */
  list(): Observable<BudgetStatus[]> {
    return this.http.get<BudgetStatus[]>(url('budgets'), silent());
  }

  project(projectId: string): Observable<BudgetStatus> {
    return this.http.get<BudgetStatus>(url('projects', projectId, 'budget'));
  }

  setProject(projectId: string, input: BudgetInput): Observable<BudgetStatus> {
    return this.http.put<BudgetStatus>(url('projects', projectId, 'budget'), input, silent());
  }

  setPlatform(input: BudgetInput): Observable<BudgetStatus> {
    return this.http.put<BudgetStatus>(url('admin', 'budget'), input, silent());
  }
}

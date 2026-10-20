import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE, params, url } from './api-base';
import type { CiExecution, CiExecutionPage } from './types';

/** Public URL of the `backoffice-ci.sh` client (served outside the API prefix as well). */
export const CI_SCRIPT_URL = `${API_BASE}/ci/script`;

export interface CiExecutionsQuery {
  projectId?: string | null;
  page?: number;
  size?: number;
  /** `field.asc` / `field.desc` (ADR 0046); omitted keeps newest first. */
  sort?: string;
}

/** Owner-side view of CI executions (`backoffice-ci run <entrypoint>` calls). */
@Injectable({ providedIn: 'root' })
export class CiApi {
  private readonly http = inject(HttpClient);

  listExecutions(query: CiExecutionsQuery): Observable<CiExecutionPage> {
    return this.http.get<CiExecutionPage>(url('ci', 'executions'), {
      params: params({
        projectId: query.projectId,
        page: query.page,
        size: query.size,
        sort: query.sort,
      }),
    });
  }

  getExecution(executionId: string): Observable<CiExecution> {
    return this.http.get<CiExecution>(url('ci', 'executions', executionId));
  }
}

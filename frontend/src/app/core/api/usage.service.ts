import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, url } from './api-base';
import type { UsageEntryPage, UsageGroupBy, UsageSummary } from './types';

export interface UsageSummaryQuery {
  from?: string | null;
  to?: string | null;
  groupBy: UsageGroupBy;
  projectId?: string | null;
}

export interface UsageEntriesQuery {
  from?: string | null;
  to?: string | null;
  runId?: string | null;
  benchmarkId?: string | null;
  page?: number;
  size?: number;
  /** `field.asc|field.desc` (occurredAt, model, kind, tokens, duration, cost); omitted = newest first. */
  sort?: string;
}

@Injectable({ providedIn: 'root' })
export class UsageApi {
  private readonly http = inject(HttpClient);

  summary(query: UsageSummaryQuery): Observable<UsageSummary> {
    return this.http.get<UsageSummary>(url('usage', 'summary'), {
      params: params({
        from: query.from,
        to: query.to,
        groupBy: query.groupBy,
        projectId: query.projectId,
      }),
    });
  }

  entries(query: UsageEntriesQuery): Observable<UsageEntryPage> {
    return this.http.get<UsageEntryPage>(url('usage', 'entries'), {
      params: params({
        from: query.from,
        to: query.to,
        runId: query.runId,
        benchmarkId: query.benchmarkId,
        page: query.page,
        size: query.size,
        sort: query.sort,
      }),
    });
  }
}

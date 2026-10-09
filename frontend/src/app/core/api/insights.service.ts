import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, url } from './api-base';
import type { DeliveryInsights, LibraryWorkflowMetricsRow, WorkflowInsights } from './types';

export interface InsightsQuery {
  /** Omit for all visible projects. */
  projectId?: string | null;
  from?: string | null;
  to?: string | null;
}

/** Delivery insights (tag `insights`, ADR 0025). */
@Injectable({ providedIn: 'root' })
export class InsightsApi {
  private readonly http = inject(HttpClient);

  delivery(query: InsightsQuery = {}): Observable<DeliveryInsights> {
    return this.http.get<DeliveryInsights>(url('insights', 'delivery'), {
      params: params({ projectId: query.projectId, from: query.from, to: query.to }),
    });
  }

  /** Metrics of one library workflow (`libraryWorkflowId`) or one project workflow (`workflowId`), ADR 0037. */
  workflow(
    scope: { libraryWorkflowId?: string | null; workflowId?: string | null },
    range: { from?: string | null; to?: string | null } = {},
  ): Observable<WorkflowInsights> {
    return this.http.get<WorkflowInsights>(url('insights', 'workflows'), {
      params: params({ ...scope, from: range.from, to: range.to }),
    });
  }

  /** Headline metrics of every library workflow with runs in the window. */
  libraryWorkflows(
    range: { from?: string | null; to?: string | null } = {},
  ): Observable<LibraryWorkflowMetricsRow[]> {
    return this.http.get<LibraryWorkflowMetricsRow[]>(url('insights', 'library-workflows'), {
      params: params({ from: range.from, to: range.to }),
    });
  }
}

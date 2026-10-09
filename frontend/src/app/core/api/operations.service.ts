import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from './api-base';
import type { OperationsOverview, WorkItemPage, WorkItemStatus } from './types';

export interface WorkItemQuery {
  status?: WorkItemStatus | null;
  kind?: string | null;
  page?: number;
  size?: number;
}

/** Platform operations (tag `operations`): replicas, the work queue and dead items. */
@Injectable({ providedIn: 'root' })
export class OperationsApi {
  private readonly http = inject(HttpClient);

  /** Polled every 10 s: silent, the page shows its own "could not refresh" state. */
  overview(): Observable<OperationsOverview> {
    return this.http.get<OperationsOverview>(url('admin', 'operations', 'overview'), silent());
  }

  workItems(query: WorkItemQuery = {}): Observable<WorkItemPage> {
    return this.http.get<WorkItemPage>(url('admin', 'operations', 'work-items'), {
      ...silent(),
      params: params({ ...query }),
    });
  }

  retry(itemId: string): Observable<void> {
    return this.http.post<void>(url('admin', 'operations', 'work-items', itemId, 'retry'), {});
  }
}

import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, url } from './api-base';
import type { DeliveryInsights } from './types';

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
}

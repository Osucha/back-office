import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type { Connection } from './types';

/**
 * `GET /connections/expiring` (ADR 0030 reminders): connections the caller manages whose token
 * expires within 14 days or has expired. Used by the *Your turn* queue; the rest of the connection
 * registry lives in `features/connections`.
 */
@Injectable({ providedIn: 'root' })
export class ConnectionRemindersApi {
  private readonly http = inject(HttpClient);

  expiring(): Observable<Connection[]> {
    return this.http.get<Connection[]>(url('connections', 'expiring'), silent());
  }
}

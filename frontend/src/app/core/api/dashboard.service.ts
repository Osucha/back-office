import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { url } from './api-base';
import type { Dashboard } from './types';

@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);

  get(): Observable<Dashboard> {
    return this.http.get<Dashboard>(url('dashboard'));
  }
}

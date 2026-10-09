import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type { IntegrationName, IntegrationTestResult, IntegrationsStatus } from './types';

@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);

  integrations(): Observable<IntegrationsStatus> {
    return this.http.get<IntegrationsStatus>(url('settings', 'integrations'));
  }

  testIntegration(name: IntegrationName): Observable<IntegrationTestResult> {
    return this.http.post<IntegrationTestResult>(
      url('settings', 'integrations', name, 'test'),
      {},
      silent(),
    );
  }
}

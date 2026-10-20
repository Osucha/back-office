import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { params, silent, url } from './api-base';
import type { ModelProfile, ModelProfileInput, ModelTestResult, ProviderStatus } from './types';

@Injectable({ providedIn: 'root' })
export class ModelProfilesApi {
  private readonly http = inject(HttpClient);

  list(enabled?: boolean): Observable<ModelProfile[]> {
    return this.http.get<ModelProfile[]>(url('model-profiles'), { params: params({ enabled }) });
  }

  /**
   * Enabled profiles the LLM gateway can call: what projects, templates and workflow steps may pick. Claude Code login
   * profiles (ADR 0048) run only in benchmarks on a personal runner.
   */
  listCallable(): Observable<ModelProfile[]> {
    return this.list(true).pipe(map((list) => list.filter((p) => p.runtime !== 'CLAUDE_CODE_LOGIN')));
  }

  get(profileId: string): Observable<ModelProfile> {
    return this.http.get<ModelProfile>(url('model-profiles', profileId));
  }

  create(input: ModelProfileInput): Observable<ModelProfile> {
    return this.http.post<ModelProfile>(url('model-profiles'), input);
  }

  update(profileId: string, input: ModelProfileInput): Observable<ModelProfile> {
    return this.http.put<ModelProfile>(url('model-profiles', profileId), input);
  }

  delete(profileId: string): Observable<void> {
    return this.http.delete<void>(url('model-profiles', profileId));
  }

  /** 502 carries a Problem; the caller renders it in the test result panel. */
  test(profileId: string): Observable<ModelTestResult> {
    return this.http.post<ModelTestResult>(url('model-profiles', profileId, 'test'), {}, silent());
  }

  providers(): Observable<ProviderStatus[]> {
    return this.http.get<ProviderStatus[]>(url('model-profiles', 'providers'));
  }
}

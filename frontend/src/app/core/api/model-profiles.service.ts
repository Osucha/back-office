import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { params, silent, url } from './api-base';
import type {
  CodingHarness,
  ModelDefaultInput,
  ModelDefaultScope,
  ModelDefaults,
  ModelOption,
  ModelProfile,
  ModelProfileInput,
  ModelTestResult,
  ProviderStatus,
  StepKind,
} from './types';

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
    return this.list(true).pipe(
      map((list) => list.filter((p) => p.runtime !== 'CLAUDE_CODE_LOGIN')),
    );
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

  /** ADR 0050 step 2: the platform default models and the caller's own, per step kind. */
  defaults(): Observable<ModelDefaults> {
    return this.http.get<ModelDefaults>(url('model-profiles', 'defaults'));
  }

  /** Sets (or with null clears) a platform default; needs models.manage. */
  setPlatformDefault(stepKind: StepKind, profileId: string | null): Observable<ModelDefaults> {
    const body: ModelDefaultInput = { stepKind, profileId };
    return this.http.put<ModelDefaults>(url('model-profiles', 'defaults', 'platform'), body);
  }

  /** Sets (or with null clears) the caller's own default. */
  setMyDefault(stepKind: StepKind, profileId: string | null): Observable<ModelDefaults> {
    const body: ModelDefaultInput = { stepKind, profileId };
    return this.http.put<ModelDefaults>(url('model-profiles', 'defaults', 'mine'), body);
  }

  /** The models that may be picked as a default of this step kind and scope. */
  defaultOptions(
    stepKind: StepKind,
    scope: ModelDefaultScope,
    harness?: CodingHarness | null,
  ): Observable<ModelOption[]> {
    return this.http.get<ModelOption[]>(url('model-profiles', 'options'), {
      params: params({ stepKind, scope, harness }),
    });
  }
}

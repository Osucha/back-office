import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type { LlmProvider, LlmProviderInput, Provider, ProviderCatalogSection, ProviderModels } from './types';

/** LLM provider connections (tag `llm-providers`, ADR 0045): API keys are write-only. */
@Injectable({ providedIn: 'root' })
export class LlmProvidersApi {
  private readonly http = inject(HttpClient);

  list(): Observable<LlmProvider[]> {
    return this.http.get<LlmProvider[]>(url('llm-providers'));
  }

  /** The models of every usable endpoint, the source of new model profiles; `refresh` asks the providers again. */
  catalog(refresh = false): Observable<ProviderCatalogSection[]> {
    return this.http.get<ProviderCatalogSection[]>(url('llm-providers', 'catalog'), { params: new HttpParams().set('refresh', refresh) });
  }

  create(input: LlmProviderInput): Observable<LlmProvider> {
    return this.http.post<LlmProvider>(url('llm-providers'), input, silent());
  }

  update(id: string, input: LlmProviderInput): Observable<LlmProvider> {
    return this.http.put<LlmProvider>(url('llm-providers', id), input, silent());
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(url('llm-providers', id));
  }

  /** Connection test: the models the endpoint lists. */
  models(id: string): Observable<ProviderModels> {
    return this.http.post<ProviderModels>(url('llm-providers', id, 'models'), {});
  }

  environmentModels(provider: Provider): Observable<ProviderModels> {
    return this.http.post<ProviderModels>(url('llm-providers', 'environment', 'models'), {}, { params: new HttpParams().set('provider', provider) });
  }
}

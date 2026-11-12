import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from './api-base';
import type { Connector, ConnectorInput, ConnectorTest } from './types';

/**
 * Connectors (ADR 0064): the tools agents use - MCP servers with credentials and GitLab read-only - of the platform
 * and of projects. Secrets are write-only: never returned, an omitted one is kept on update, an empty one removed.
 */
@Injectable({ providedIn: 'root' })
export class ConnectorsApi {
  private readonly http = inject(HttpClient);

  /** The platform connectors and, with a project, that project's own. */
  list(projectId?: string | null): Observable<Connector[]> {
    return this.http.get<Connector[]>(url('connectors'), { params: params({ projectId }) });
  }

  create(input: ConnectorInput): Observable<Connector> {
    return this.http.post<Connector>(url('connectors'), input, silent());
  }

  update(id: string, input: ConnectorInput): Observable<Connector> {
    return this.http.put<Connector>(url('connectors', id), input, silent());
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(url('connectors', id));
  }

  /** Reachability (and the MCP handshake) from Back Office; the result is stored on the connector. */
  test(id: string): Observable<ConnectorTest> {
    return this.http.post<ConnectorTest>(url('connectors', id, 'test'), null, silent());
  }
}

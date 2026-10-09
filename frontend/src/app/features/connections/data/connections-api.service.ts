import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from '../../../core/api/api-base';
import type {
  Connection,
  ConnectionInput,
  ConnectionOwner,
  ConnectionResolution,
  ConnectionSecretInput,
  ConnectionVerification,
  PersonalConnectionSummary,
  ProjectJiraConnections,
  ProjectJiraConnectionsInput,
} from './connection-types';

/**
 * `connections` tag (ADR 0030, ADR 0035): platform, project and personal connections, the project's
 * system connection and list, usable list and resolution.
 */
@Injectable({ providedIn: 'root' })
export class ConnectionsApi {
  private readonly http = inject(HttpClient);

  private base(owner: ConnectionOwner, ...rest: string[]): string {
    switch (owner.kind) {
      case 'platform':
        return url('admin', 'connections', ...rest);
      case 'personal':
        return url('me', 'connections', ...rest);
      default:
        return url('projects', owner.projectId, 'connections', ...rest);
    }
  }

  list(owner: ConnectionOwner): Observable<Connection[]> {
    return this.http.get<Connection[]>(this.base(owner));
  }

  /** 400 (validation) is shown by the caller. */
  create(owner: ConnectionOwner, input: ConnectionInput): Observable<Connection> {
    return this.http.post<Connection>(this.base(owner), input, silent());
  }

  /** Omitting `secret` keeps the stored token. 400 is shown by the caller. */
  update(owner: ConnectionOwner, id: string, input: ConnectionInput): Observable<Connection> {
    return this.http.put<Connection>(this.base(owner, id), input, silent());
  }

  rotateSecret(
    owner: ConnectionOwner,
    id: string,
    input: ConnectionSecretInput,
  ): Observable<Connection> {
    return this.http.put<Connection>(this.base(owner, id, 'secret'), input, silent());
  }

  remove(owner: ConnectionOwner, id: string): Observable<void> {
    return this.http.delete<void>(this.base(owner, id));
  }

  /** Calls Jira now; the result is stored (not for the legacy connection) and returned. */
  verify(owner: ConnectionOwner, id: string, projectKey?: string | null): Observable<Connection> {
    return this.http.post<Connection>(
      this.base(owner, id, 'verify'),
      { projectKey: projectKey || null },
      silent(),
    );
  }

  /**
   * "Test before saving": checks a draft against Jira without storing anything. Without a token in
   * `input`, the stored token of `connectionId` is used (editing). Errors are shown by the caller.
   */
  testDraft(
    owner: ConnectionOwner,
    input: ConnectionInput,
    connectionId?: string | null,
    projectKey?: string | null,
  ): Observable<ConnectionVerification> {
    return this.http.post<ConnectionVerification>(
      this.base(owner, 'test'),
      { connection: input, connectionId: connectionId || null, projectKey: projectKey || null },
      silent(),
    );
  }

  /** Administrators: metadata of every personal connection (never usable by them). */
  personalSummaries(): Observable<PersonalConnectionSummary[]> {
    return this.http.get<PersonalConnectionSummary[]>(url('admin', 'connections', 'personal'));
  }

  /** The project's system connection and further connections (ADR 0035). */
  projectJira(projectId: string): Observable<ProjectJiraConnections> {
    return this.http.get<ProjectJiraConnections>(url('projects', projectId, 'jira-connections'));
  }

  /** 400 (a personal or unknown connection) is shown by the caller. */
  updateProjectJira(
    projectId: string,
    input: ProjectJiraConnectionsInput,
  ): Observable<ProjectJiraConnections> {
    return this.http.put<ProjectJiraConnections>(
      url('projects', projectId, 'jira-connections'),
      input,
      silent(),
    );
  }

  /** Project connections, then platform ones, then the legacy credential (selection order). */
  usable(projectId: string): Observable<Connection[]> {
    return this.http.get<Connection[]>(url('projects', projectId, 'connections', 'usable'));
  }

  resolve(
    projectId: string,
    capabilities: readonly string[],
    connectionId?: string | null,
    jiraProjectKey?: string | null,
  ): Observable<ConnectionResolution> {
    return this.http.get<ConnectionResolution>(
      url('projects', projectId, 'connections', 'resolve'),
      {
        params: params({ capabilities, connectionId, jiraProjectKey }),
        ...silent(),
      },
    );
  }
}

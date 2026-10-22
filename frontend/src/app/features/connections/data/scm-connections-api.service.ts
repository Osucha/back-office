import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from '../../../core/api/api-base';
import type { ScmConnection, ScmConnectionInput, ScmVerification } from './connection-types';

/**
 * Tokens of a project's repositories (ADR 0051, `/projects/{id}/scm-connections`). Reading needs
 * `project.view`, every write `project.connections.manage`. Tokens are write-only: never returned.
 */
@Injectable({ providedIn: 'root' })
export class ScmConnectionsApi {
  private readonly http = inject(HttpClient);

  private base(projectId: string, ...rest: string[]): string {
    return url('projects', projectId, 'scm-connections', ...rest);
  }

  list(projectId: string): Observable<ScmConnection[]> {
    return this.http.get<ScmConnection[]>(this.base(projectId));
  }

  /** The token is tested before it is stored; a failed test does not block the save. 400 is shown by the caller. */
  create(projectId: string, input: ScmConnectionInput): Observable<ScmConnection> {
    return this.http.post<ScmConnection>(this.base(projectId), input, silent());
  }

  /** "Test before saving": nothing is stored; `token` is required. Errors are shown by the caller. */
  test(projectId: string, input: ScmConnectionInput): Observable<ScmVerification> {
    return this.http.post<ScmVerification>(this.base(projectId, 'test'), input, silent());
  }

  /** Without `token` the stored token is kept; a new `token` replaces it. Tested again either way. */
  update(projectId: string, id: string, input: ScmConnectionInput): Observable<ScmConnection> {
    return this.http.put<ScmConnection>(this.base(projectId, id), input, silent());
  }

  remove(projectId: string, id: string): Observable<void> {
    return this.http.delete<void>(this.base(projectId, id));
  }

  /** Tests the stored token again; 409 when its repository is no longer part of the project. */
  verify(projectId: string, id: string): Observable<ScmConnection> {
    return this.http.post<ScmConnection>(this.base(projectId, id, 'verify'), null, silent());
  }
}

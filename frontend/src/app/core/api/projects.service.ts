import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type {
  CiEntrypoint,
  CiSnippet,
  CiToken,
  CiTokenCreated,
  Project,
  ProjectInput,
  SpecKitStatus,
  WorkflowDefinition,
} from './types';

@Injectable({ providedIn: 'root' })
export class ProjectsApi {
  private readonly http = inject(HttpClient);

  list(): Observable<Project[]> {
    return this.http.get<Project[]>(url('projects'));
  }

  get(projectId: string): Observable<Project> {
    return this.http.get<Project>(url('projects', projectId));
  }

  create(input: ProjectInput): Observable<Project> {
    // Field errors are mapped onto the project form by the caller.
    return this.http.post<Project>(url('projects'), input, silent());
  }

  update(projectId: string, input: ProjectInput): Observable<Project> {
    return this.http.put<Project>(url('projects', projectId), input, silent());
  }

  delete(projectId: string): Observable<void> {
    return this.http.delete<void>(url('projects', projectId));
  }

  /** ADR 0036: the Spec Kit setting, the platform switch and the missing Spec Kit workflows. */
  specKit(projectId: string): Observable<SpecKitStatus> {
    return this.http.get<SpecKitStatus>(url('projects', projectId, 'spec-kit'), silent());
  }

  /** ADR 0036: "Add Spec Kit workflows"; errors are shown by the caller. */
  addSpecKitWorkflows(projectId: string): Observable<WorkflowDefinition[]> {
    return this.http.post<WorkflowDefinition[]>(
      url('projects', projectId, 'spec-kit', 'workflows'),
      {},
      silent(),
    );
  }

  // ---- CI entrypoints (entrypoint key -> workflow)

  ciEntrypoints(projectId: string): Observable<CiEntrypoint[]> {
    return this.http.get<CiEntrypoint[]>(url('projects', projectId, 'ci-entrypoints'));
  }

  replaceCiEntrypoints(projectId: string, entrypoints: CiEntrypoint[]): Observable<CiEntrypoint[]> {
    return this.http.put<CiEntrypoint[]>(url('projects', projectId, 'ci-entrypoints'), entrypoints);
  }

  // ---- CI tokens (plaintext returned once on creation)

  ciTokens(projectId: string): Observable<CiToken[]> {
    return this.http.get<CiToken[]>(url('projects', projectId, 'ci-tokens'));
  }

  createCiToken(projectId: string, name: string): Observable<CiTokenCreated> {
    return this.http.post<CiTokenCreated>(url('projects', projectId, 'ci-tokens'), { name });
  }

  revokeCiToken(projectId: string, tokenId: string): Observable<void> {
    return this.http.delete<void>(url('projects', projectId, 'ci-tokens', tokenId));
  }

  /** Ready-to-paste `.gitlab-ci.yml` include and variables for this project. */
  ciSnippet(projectId: string): Observable<CiSnippet> {
    return this.http.get<CiSnippet>(url('projects', projectId, 'ci-snippet'));
  }
}

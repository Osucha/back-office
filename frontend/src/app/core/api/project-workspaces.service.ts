import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type { ProjectWorkspace, ProjectWorkspaceInput } from './types';

/** Run workspaces of a project (tag `project-workspaces`, ADR 0044), picked by steps of its workflows. */
@Injectable({ providedIn: 'root' })
export class ProjectWorkspacesApi {
  private readonly http = inject(HttpClient);

  list(projectId: string): Observable<ProjectWorkspace[]> {
    return this.http.get<ProjectWorkspace[]>(url('projects', projectId, 'workspaces'));
  }

  create(projectId: string, input: ProjectWorkspaceInput): Observable<ProjectWorkspace> {
    return this.http.post<ProjectWorkspace>(url('projects', projectId, 'workspaces'), input, silent());
  }

  update(projectId: string, id: string, input: ProjectWorkspaceInput): Observable<ProjectWorkspace> {
    return this.http.put<ProjectWorkspace>(url('projects', projectId, 'workspaces', id), input, silent());
  }

  delete(projectId: string, id: string): Observable<void> {
    return this.http.delete<void>(url('projects', projectId, 'workspaces', id));
  }
}

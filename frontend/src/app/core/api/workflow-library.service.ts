import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, url } from './api-base';
import type {
  AdoptLibraryWorkflowRequest,
  LibraryUpdateRequest,
  LibraryWorkflow,
  LibraryWorkflowSummary,
  LibraryWorkflowUpdate,
  LibraryWorkflowVersionDetail,
  PublishWorkflowRequest,
  WorkflowDefinition,
} from './types';

/** Workflow library (tag `workflow-library`, ADR 0037): entries, publishing and linked project copies. */
@Injectable({ providedIn: 'root' })
export class WorkflowLibraryApi {
  private readonly http = inject(HttpClient);

  list(includeDeprecated = true): Observable<LibraryWorkflowSummary[]> {
    return this.http.get<LibraryWorkflowSummary[]>(url('library', 'workflows'), {
      params: params({ includeDeprecated }),
    });
  }

  get(id: string): Observable<LibraryWorkflow> {
    return this.http.get<LibraryWorkflow>(url('library', 'workflows', id));
  }

  version(id: string, version: number): Observable<LibraryWorkflowVersionDetail> {
    return this.http.get<LibraryWorkflowVersionDetail>(
      url('library', 'workflows', id, 'versions', String(version)),
    );
  }

  update(id: string, body: LibraryWorkflowUpdate): Observable<LibraryWorkflow> {
    return this.http.put<LibraryWorkflow>(url('library', 'workflows', id), body);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(url('library', 'workflows', id));
  }

  /** Publishes a saved project workflow as a new entry or the next version of one. */
  publish(workflowId: string, body: PublishWorkflowRequest): Observable<LibraryWorkflow> {
    return this.http.post<LibraryWorkflow>(url('workflows', workflowId, 'publish'), body);
  }

  /** Adds a linked copy of a library version to a project. */
  adopt(projectId: string, body: AdoptLibraryWorkflowRequest): Observable<WorkflowDefinition> {
    return this.http.post<WorkflowDefinition>(
      url('projects', projectId, 'workflows', 'from-library'),
      body,
    );
  }

  /** Moves a linked copy to a library version (default the latest), keeping project-local settings. */
  updateFromLibrary(
    workflowId: string,
    body: LibraryUpdateRequest = { discardLocalChanges: false },
  ): Observable<WorkflowDefinition> {
    return this.http.post<WorkflowDefinition>(
      url('workflows', workflowId, 'library', 'update'),
      body,
    );
  }

  detach(workflowId: string): Observable<WorkflowDefinition> {
    return this.http.post<WorkflowDefinition>(
      url('workflows', workflowId, 'library', 'detach'),
      {},
    );
  }
}

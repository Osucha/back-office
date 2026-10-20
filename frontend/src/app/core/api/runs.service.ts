import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from './api-base';
import type {
  Artifact,
  ArtifactSummary,
  LogLine,
  ManualSignal,
  RunStatus,
  WorkflowRun,
  WorkflowRunPage,
} from './types';

export interface RunsQuery {
  projectId?: string | null;
  workflowId?: string | null;
  status?: RunStatus[];
  page?: number;
  size?: number;
  /** `field.asc` / `field.desc` (ADR 0046); omitted keeps newest first. */
  sort?: string;
}

@Injectable({ providedIn: 'root' })
export class RunsApi {
  private readonly http = inject(HttpClient);

  list(query: RunsQuery): Observable<WorkflowRunPage> {
    return this.http.get<WorkflowRunPage>(url('runs'), {
      params: params({
        projectId: query.projectId,
        workflowId: query.workflowId,
        status: query.status,
        page: query.page,
        size: query.size,
        sort: query.sort,
      }),
    });
  }

  get(runId: string): Observable<WorkflowRun> {
    return this.http.get<WorkflowRun>(url('runs', runId));
  }

  artifacts(runId: string): Observable<ArtifactSummary[]> {
    return this.http.get<ArtifactSummary[]>(url('runs', runId, 'artifacts'));
  }

  artifact(runId: string, artifactId: string): Observable<Artifact> {
    return this.http.get<Artifact>(url('runs', runId, 'artifacts', artifactId));
  }

  logs(
    runId: string,
    opts: { nodeId?: string | null; after?: string | null } = {},
  ): Observable<LogLine[]> {
    return this.http.get<LogLine[]>(url('runs', runId, 'logs'), {
      params: params({ nodeId: opts.nodeId, after: opts.after }),
      ...silent(),
    });
  }

  cancel(runId: string): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(url('runs', runId, 'cancel'), {});
  }

  retry(runId: string): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(url('runs', runId, 'retry'), {});
  }

  /** Owner-side equivalent of a CI signal, e.g. to unblock a WAIT_FOR_CI node by hand. */
  signal(runId: string, signal: ManualSignal): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(url('runs', runId, 'signals'), signal);
  }
}

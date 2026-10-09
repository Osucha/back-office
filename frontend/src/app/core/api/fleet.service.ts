import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from './api-base';
import type {
  AgentJob,
  AgentJobPage,
  AgentJobQuery,
  RegistrationToken,
  Runner,
  RunnerPool,
  RunnerPoolInput,
  SandboxPolicy,
  SandboxPolicyView,
} from './types';

/** Runner fleet administration (tag `fleet`, ADR 0024) and the project sandbox policy. */
@Injectable({ providedIn: 'root' })
export class FleetApi {
  private readonly http = inject(HttpClient);

  pools(): Observable<RunnerPool[]> {
    return this.http.get<RunnerPool[]>(url('admin', 'runner-pools'));
  }

  pool(poolId: string): Observable<RunnerPool> {
    return this.http.get<RunnerPool>(url('admin', 'runner-pools', poolId));
  }

  createPool(input: RunnerPoolInput): Observable<RunnerPool> {
    return this.http.post<RunnerPool>(url('admin', 'runner-pools'), input, silent());
  }

  updatePool(poolId: string, input: RunnerPoolInput): Observable<RunnerPool> {
    return this.http.put<RunnerPool>(url('admin', 'runner-pools', poolId), input, silent());
  }

  deletePool(poolId: string): Observable<void> {
    return this.http.delete<void>(url('admin', 'runner-pools', poolId));
  }

  /** Single-use registration token for a runner-controller (shown once, 24 h). */
  createRegistrationToken(poolId: string): Observable<RegistrationToken> {
    return this.http.post<RegistrationToken>(
      url('admin', 'runner-pools', poolId, 'registration-tokens'),
      {},
    );
  }

  runners(poolId?: string | null): Observable<Runner[]> {
    return this.http.get<Runner[]>(url('admin', 'runners'), { params: params({ poolId }) });
  }

  /** Revokes the runner token; its leased jobs are re-queued. */
  revokeRunner(runnerId: string): Observable<void> {
    return this.http.delete<void>(url('admin', 'runners', runnerId));
  }

  jobs(query: AgentJobQuery = {}): Observable<AgentJobPage> {
    return this.http.get<AgentJobPage>(url('admin', 'agent-jobs'), {
      params: params({ ...query }),
    });
  }

  job(jobId: string): Observable<AgentJob> {
    return this.http.get<AgentJob>(url('admin', 'agent-jobs', jobId));
  }

  cancelJob(jobId: string): Observable<void> {
    return this.http.post<void>(url('admin', 'agent-jobs', jobId, 'cancel'), {});
  }

  projectPolicy(projectId: string): Observable<SandboxPolicyView> {
    return this.http.get<SandboxPolicyView>(url('projects', projectId, 'sandbox-policy'));
  }

  updateProjectPolicy(projectId: string, policy: SandboxPolicy): Observable<SandboxPolicyView> {
    return this.http.put<SandboxPolicyView>(
      url('projects', projectId, 'sandbox-policy'),
      policy,
      silent(),
    );
  }
}

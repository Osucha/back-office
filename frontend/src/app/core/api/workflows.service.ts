import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from './api-base';
import type {
  CodingHarness,
  ExpressionValidation,
  ModelOption,
  StartRunRequest,
  StepKind,
  StepModelPlan,
  ValidationResult,
  WorkflowDefinition,
  WorkflowDefinitionInput,
  WorkflowPalette,
  WorkflowRun,
} from './types';

@Injectable({ providedIn: 'root' })
export class WorkflowsApi {
  private readonly http = inject(HttpClient);

  listForProject(projectId: string): Observable<WorkflowDefinition[]> {
    return this.http.get<WorkflowDefinition[]>(url('projects', projectId, 'workflows'));
  }

  create(projectId: string, input: WorkflowDefinitionInput): Observable<WorkflowDefinition> {
    return this.http.post<WorkflowDefinition>(url('projects', projectId, 'workflows'), input);
  }

  get(workflowId: string): Observable<WorkflowDefinition> {
    return this.http.get<WorkflowDefinition>(url('workflows', workflowId));
  }

  update(workflowId: string, input: WorkflowDefinitionInput): Observable<WorkflowDefinition> {
    return this.http.put<WorkflowDefinition>(url('workflows', workflowId), input);
  }

  delete(workflowId: string): Observable<void> {
    return this.http.delete<void>(url('workflows', workflowId));
  }

  duplicate(workflowId: string): Observable<WorkflowDefinition> {
    return this.http.post<WorkflowDefinition>(url('workflows', workflowId, 'duplicate'), {});
  }

  /** Server-side validation; issues are surfaced inline by the designer, so no toast. */
  validate(projectId: string, input: WorkflowDefinitionInput): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(url('workflows', 'validate'), input, {
      params: params({ projectId }),
      ...silent(),
    });
  }

  /** Control nodes + task templates (grouped by interface) available to the designer. */
  palette(projectId: string): Observable<WorkflowPalette> {
    return this.http.get<WorkflowPalette>(url('workflows', 'palette'), {
      params: params({ projectId }),
    });
  }

  /** ADR 0050: the models a step of the given kind may use for the caller, usable first. */
  modelOptions(
    projectId: string,
    stepKind: StepKind,
    harness?: CodingHarness | null,
  ): Observable<ModelOption[]> {
    return this.http.get<ModelOption[]>(url('workflows', 'model-options'), {
      params: params({ projectId, stepKind, harness }),
    });
  }

  /** ADR 0050: the model of every task step as a run would resolve it; a step with a problem blocks a run. */
  modelPlan(workflowId: string): Observable<StepModelPlan[]> {
    return this.http.get<StepModelPlan[]>(url('workflows', workflowId, 'model-plan'), silent());
  }

  validateExpression(expression: string): Observable<ExpressionValidation> {
    return this.http.post<ExpressionValidation>(
      url('workflows', 'expressions', 'validate'),
      { expression },
      silent(),
    );
  }

  startRun(workflowId: string, request: StartRunRequest): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(url('workflows', workflowId, 'runs'), request);
  }
}

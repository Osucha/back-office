import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, shareReplay, throwError } from 'rxjs';
import { params, silent, url } from './api-base';
import type {
  Agent,
  AgentInput,
  CoherenceReport,
  ResolvedTask,
  Rule,
  RuleInput,
  Skill,
  SkillInput,
  TaskConfigSchemas,
  TaskOverrides,
  TaskTemplate,
  TaskTemplateInput,
} from './types';

/** Task Library: templates (classes with inheritance), skills, rules (ADR 0055) and agents (ADR 0056). */
@Injectable({ providedIn: 'root' })
export class TaskLibraryApi {
  private readonly http = inject(HttpClient);

  // ---- templates

  listTemplates(query: { projectId?: string | null; includeAbstract?: boolean } = {}) {
    return this.http.get<TaskTemplate[]>(url('task-templates'), {
      params: params({ projectId: query.projectId, includeAbstract: query.includeAbstract }),
    });
  }

  getTemplate(templateId: string): Observable<TaskTemplate> {
    return this.http.get<TaskTemplate>(url('task-templates', templateId));
  }

  createTemplate(input: TaskTemplateInput): Observable<TaskTemplate> {
    return this.http.post<TaskTemplate>(url('task-templates'), input);
  }

  updateTemplate(templateId: string, input: TaskTemplateInput): Observable<TaskTemplate> {
    return this.http.put<TaskTemplate>(url('task-templates', templateId), input);
  }

  deleteTemplate(templateId: string): Observable<void> {
    return this.http.delete<void>(url('task-templates', templateId));
  }

  /** Effective merged task (lineage, prompts, skills, rules, check); 404 handled by the caller. */
  resolveTemplate(key: string, projectId?: string | null): Observable<ResolvedTask> {
    return this.http.get<ResolvedTask>(url('task-templates', 'resolve', key), {
      params: params({ projectId }),
      ...silent(),
    });
  }

  /**
   * ADR 0054: what a workflow step gets - the template with the step's overrides applied
   * (prompts, skills, config, model); errors handled by the caller.
   */
  resolveStep(
    key: string,
    overrides: TaskOverrides,
    projectId?: string | null,
  ): Observable<ResolvedTask> {
    return this.http.post<ResolvedTask>(url('task-templates', 'resolve', key), overrides, {
      params: params({ projectId }),
      ...silent(),
    });
  }

  /**
   * ADR 0056: the JSON Schema of a step's config for a model answer and for a coding agent
   * (static; loaded once).
   */
  taskConfigSchemas(): Observable<TaskConfigSchemas> {
    this.configSchemas$ ??= this.http
      .get<TaskConfigSchemas>(url('workflows', 'task-config-schemas'), silent())
      .pipe(
        catchError((e: unknown) => {
          this.configSchemas$ = undefined;
          return throwError(() => e);
        }),
        shareReplay(1),
      );
    return this.configSchemas$;
  }
  private configSchemas$?: Observable<TaskConfigSchemas>;

  // ---- agents (ADR 0056)

  /** Global agents plus, with a project, that project's own. */
  listAgents(projectId?: string | null): Observable<Agent[]> {
    return this.http.get<Agent[]>(url('agents'), { params: params({ projectId }) });
  }

  getAgent(agentId: string): Observable<Agent> {
    return this.http.get<Agent>(url('agents', agentId));
  }

  createAgent(input: AgentInput): Observable<Agent> {
    return this.http.post<Agent>(url('agents'), input);
  }

  updateAgent(agentId: string, input: AgentInput): Observable<Agent> {
    return this.http.put<Agent>(url('agents', agentId), input);
  }

  /** Refused (409) while a template uses the agent. */
  deleteAgent(agentId: string): Observable<void> {
    return this.http.delete<void>(url('agents', agentId));
  }

  /**
   * ADR 0061: a model reads the agent's whole environment (with a project also the primary
   * repository's AGENTS.md / CLAUDE.md) and lists contradictions; costs one model call. 409 when no
   * model is configured; errors handled by the caller.
   */
  checkAgentCoherence(agentId: string, projectId?: string | null): Observable<CoherenceReport> {
    return this.http.post<CoherenceReport>(
      url('agents', agentId, 'coherence-check'),
      {},
      { params: params({ projectId }), ...silent() },
    );
  }

  // ---- skills

  listSkills(projectId?: string | null): Observable<Skill[]> {
    return this.http.get<Skill[]>(url('skills'), { params: params({ projectId }) });
  }

  getSkill(skillId: string): Observable<Skill> {
    return this.http.get<Skill>(url('skills', skillId));
  }

  createSkill(input: SkillInput): Observable<Skill> {
    return this.http.post<Skill>(url('skills'), input);
  }

  updateSkill(skillId: string, input: SkillInput): Observable<Skill> {
    return this.http.put<Skill>(url('skills', skillId), input);
  }

  deleteSkill(skillId: string): Observable<void> {
    return this.http.delete<void>(url('skills', skillId));
  }

  // ---- rules (ADR 0055)

  /** Global rules plus, with a project, that project's own. */
  listRules(projectId?: string | null): Observable<Rule[]> {
    return this.http.get<Rule[]>(url('rules'), { params: params({ projectId }) });
  }

  getRule(ruleId: string): Observable<Rule> {
    return this.http.get<Rule>(url('rules', ruleId));
  }

  createRule(input: RuleInput): Observable<Rule> {
    return this.http.post<Rule>(url('rules'), input);
  }

  updateRule(ruleId: string, input: RuleInput): Observable<Rule> {
    return this.http.put<Rule>(url('rules', ruleId), input);
  }

  /** Refused (409) while an agent lists the rule (ADR 0057). */
  deleteRule(ruleId: string): Observable<void> {
    return this.http.delete<void>(url('rules', ruleId));
  }
}

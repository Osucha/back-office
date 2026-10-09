import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { params, silent, url } from './api-base';
import type {
  ResolvedTask,
  Skill,
  SkillInput,
  TaskInterface,
  TaskInterfaceInput,
  TaskTemplate,
  TaskTemplateInput,
} from './types';

/** Task Library: interfaces (contracts), templates (classes with inheritance) and skills. */
@Injectable({ providedIn: 'root' })
export class TaskLibraryApi {
  private readonly http = inject(HttpClient);

  // ---- interfaces

  listInterfaces(): Observable<TaskInterface[]> {
    return this.http.get<TaskInterface[]>(url('task-interfaces'));
  }

  getInterface(key: string): Observable<TaskInterface> {
    return this.http.get<TaskInterface>(url('task-interfaces', key));
  }

  createInterface(input: TaskInterfaceInput): Observable<TaskInterface> {
    return this.http.post<TaskInterface>(url('task-interfaces'), input);
  }

  updateInterface(key: string, input: TaskInterfaceInput): Observable<TaskInterface> {
    return this.http.put<TaskInterface>(url('task-interfaces', key), input);
  }

  deleteInterface(key: string): Observable<void> {
    return this.http.delete<void>(url('task-interfaces', key));
  }

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

  /** Effective merged task (lineage, prompts, skills, interface check); 404 handled by the caller. */
  resolveTemplate(key: string, projectId?: string | null): Observable<ResolvedTask> {
    return this.http.get<ResolvedTask>(url('task-templates', 'resolve', key), {
      params: params({ projectId }),
      ...silent(),
    });
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
}

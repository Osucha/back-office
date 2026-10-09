import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type { JiraIntake, JiraIntakeInput } from './types';

/** `jira-intake` tag (ADR 0033): the project's Jira intake watcher. */
@Injectable({ providedIn: 'root' })
export class JiraIntakeApi {
  private readonly http = inject(HttpClient);

  get(projectId: string): Observable<JiraIntake> {
    return this.http.get<JiraIntake>(url('projects', projectId, 'jira-intake'));
  }

  /** 400 (no Jira trigger, interval out of range) is shown by the caller. */
  update(projectId: string, input: JiraIntakeInput): Observable<JiraIntake> {
    return this.http.put<JiraIntake>(url('projects', projectId, 'jira-intake'), input, silent());
  }

  /** "Poll now": a worker polls right away (202). */
  pollNow(projectId: string): Observable<JiraIntake> {
    return this.http.post<JiraIntake>(
      url('projects', projectId, 'jira-intake', 'poll'),
      null,
      silent(),
    );
  }
}

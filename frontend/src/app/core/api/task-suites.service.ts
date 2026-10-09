import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { url } from './api-base';
import type { EvalTask, EvalTaskInput, TaskSuite, TaskSuiteInput, TaskSuiteSummary } from './types';

@Injectable({ providedIn: 'root' })
export class TaskSuitesApi {
  private readonly http = inject(HttpClient);

  list(): Observable<TaskSuiteSummary[]> {
    return this.http.get<TaskSuiteSummary[]>(url('task-suites'));
  }

  get(suiteId: string): Observable<TaskSuite> {
    return this.http.get<TaskSuite>(url('task-suites', suiteId));
  }

  create(input: TaskSuiteInput): Observable<TaskSuite> {
    return this.http.post<TaskSuite>(url('task-suites'), input);
  }

  update(suiteId: string, input: TaskSuiteInput): Observable<TaskSuite> {
    return this.http.put<TaskSuite>(url('task-suites', suiteId), input);
  }

  delete(suiteId: string): Observable<void> {
    return this.http.delete<void>(url('task-suites', suiteId));
  }

  addTask(suiteId: string, input: EvalTaskInput): Observable<EvalTask> {
    return this.http.post<EvalTask>(url('task-suites', suiteId, 'tasks'), input);
  }

  updateTask(suiteId: string, taskId: string, input: EvalTaskInput): Observable<EvalTask> {
    return this.http.put<EvalTask>(url('task-suites', suiteId, 'tasks', taskId), input);
  }

  deleteTask(suiteId: string, taskId: string): Observable<void> {
    return this.http.delete<void>(url('task-suites', suiteId, 'tasks', taskId));
  }
}

import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type { ProjectMember, ProjectMemberInput } from './types';

/** `members` tag: project memberships of users and groups. */
@Injectable({ providedIn: 'root' })
export class MembersApi {
  private readonly http = inject(HttpClient);

  list(projectId: string): Observable<ProjectMember[]> {
    return this.http.get<ProjectMember[]>(url('projects', projectId, 'members'));
  }

  /** 409 (duplicate member) is shown by the caller. */
  add(projectId: string, input: ProjectMemberInput): Observable<ProjectMember> {
    return this.http.post<ProjectMember>(url('projects', projectId, 'members'), input, silent());
  }

  /** 409 (last owner) is shown by the caller. */
  changeRole(projectId: string, memberId: string, role: string): Observable<ProjectMember> {
    return this.http.put<ProjectMember>(
      url('projects', projectId, 'members', memberId),
      { role },
      silent(),
    );
  }

  /** 409 (last owner) is shown by the caller. */
  remove(projectId: string, memberId: string): Observable<void> {
    return this.http.delete<void>(url('projects', projectId, 'members', memberId), silent());
  }
}

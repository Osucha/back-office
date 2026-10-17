import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silent, url } from './api-base';
import type { LibrarySource, LibrarySourceInput } from './types';

/** Sources of the library (tag `library-sources`, ADR 0043): git repositories steps attach read-only. */
@Injectable({ providedIn: 'root' })
export class LibrarySourcesApi {
  private readonly http = inject(HttpClient);

  list(): Observable<LibrarySource[]> {
    return this.http.get<LibrarySource[]>(url('library', 'sources'));
  }

  create(input: LibrarySourceInput): Observable<LibrarySource> {
    return this.http.post<LibrarySource>(url('library', 'sources'), input, silent());
  }

  update(id: string, input: LibrarySourceInput): Observable<LibrarySource> {
    return this.http.put<LibrarySource>(url('library', 'sources', id), input, silent());
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(url('library', 'sources', id));
  }
}

import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzTableModule } from 'ng-zorro-antd/table';
import { LibrarySourcesApi } from '../../../core/api/library-sources.service';
import type { LibrarySource } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { purposeLabel, shortRepoUrl } from '../../../core/ui/repositories';

/**
 * Project tab Sources, right column (ADR 0043): the library sources every step of this project may attach
 * read-only. They are managed in Library → Sources; a step picks them in the designer.
 */
@Component({
  selector: 'app-project-sources-panel',
  imports: [RouterLink, NzCardModule, NzTableModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-card nzTitle="Library sources" i18n-nzTitle="@@projects.sources.library.title">
      <p class="muted" i18n="@@projects.sources.library.intro">
        Shared by all projects: standards, templates and reference material. Steps attach them read-only in
        the designer (section Repositories of a step); the job gets them in /workspace/lib-&lt;key&gt;.
      </p>
      @if (!loaded()) {
        <p class="muted" i18n="@@projects.sources.library.loading">Loading…</p>
      } @else if (!items().length) {
        <p class="muted" i18n="@@projects.sources.library.empty">The library has no sources yet.</p>
      } @else {
        <nz-table [nzData]="items()" [nzShowPagination]="false" nzSize="small">
          <thead>
            <tr>
              <th i18n="@@projects.sources.library.col.source">Source</th>
              <th i18n="@@projects.sources.library.col.purpose">Purpose</th>
              <th i18n="@@projects.sources.library.col.repository">Repository</th>
            </tr>
          </thead>
          <tbody>
            @for (s of items(); track s.id) {
              <tr>
                <td>
                  <div>{{ s.name }}</div>
                  <div class="mono muted">{{ s.jobKey }}</div>
                </td>
                <td>{{ purpose(s.purpose) }}</td>
                <td class="mono">{{ shortUrl(s.url) }}</td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
      @if (canView) {
        <a routerLink="/library/sources" class="more" i18n="@@projects.sources.library.manage"
          >Open Library → Sources</a
        >
      }
    </nz-card>
  `,
  styles: [
    `
      .muted {
        color: var(--bo-ink-3);
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .more {
        display: inline-block;
        margin-top: 12px;
      }
    `,
  ],
})
export class ProjectSourcesPanelComponent {
  private readonly api = inject(LibrarySourcesApi);

  readonly items = signal<LibrarySource[]>([]);
  readonly loaded = signal(false);
  readonly canView = inject(IdentityService).can('library.view');
  readonly purpose = purposeLabel;
  readonly shortUrl = shortRepoUrl;

  constructor() {
    this.api.list().subscribe({
      next: (list) => {
        this.items.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }
}

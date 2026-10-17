import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { LibrarySourcesApi } from '../../../core/api/library-sources.service';
import {
  REPOSITORY_PURPOSES,
  type LibrarySource,
  type LibrarySourceInput,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { purposeLabel, shortRepoUrl } from '../../../core/ui/repositories';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { LibraryNavComponent } from '../components/library-nav.component';
import { SOURCE_KEY_PATTERN, emptySource, sourceInputOf, sourceProblems } from '../data/library-sources';

/**
 * Library: sources (ADR 0043). Git repositories with standards, templates or reference material that steps of every
 * project attach read-only (`/workspace/lib-<key>` in the job). Curators (`library.manage`) add and edit them.
 */
@Component({
  selector: 'app-library-sources-page',
  imports: [
    FormsModule,
    NzButtonModule,
    NzFormModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    NzTableModule,
    EmptyComponent,
    LibraryNavComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="librarySources"
        title="Library"
        i18n-title="@@library.sources.header.title"
        subtitle="Sources steps can attach: repositories of standards to follow, templates and reference material, the same for every project."
        i18n-subtitle="@@library.sources.header.subtitle"
      >
        @if (canManage()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <span i18n="@@library.sources.create">Add source</span>
          </button>
        }
        <app-library-nav boHeaderBelow active="sources" [counts]="{ sources: items().length }" />
      </bo-page-header>

      @if (loaded() && !items().length) {
        <bo-empty
          title="No sources yet"
          i18n-title="@@library.sources.empty.title"
          detail="Add a git repository with your standards or templates; steps of every project can then attach it read-only."
          i18n-detail="@@library.sources.empty.detail"
        />
      } @else {
        <nz-table
          #t
          [nzData]="items()"
          [nzLoading]="!loaded()"
          [nzShowPagination]="false"
          nzSize="middle"
          class="relay-table"
        >
          <thead>
            <tr>
              <th i18n="@@library.sources.col.source">Source</th>
              <th i18n="@@library.sources.col.purpose">Purpose</th>
              <th i18n="@@library.sources.col.repository">Repository</th>
              <th i18n="@@library.sources.col.inJob">In a job</th>
              <th i18n="@@library.sources.col.changed">Changed</th>
            </tr>
          </thead>
          <tbody>
            @for (s of t.data; track s.id) {
              <tr class="row" tabindex="0" (click)="edit(s)" (keydown.enter)="edit(s)">
                <td>
                  <div class="name">{{ s.name }}</div>
                  <div class="mono muted small">{{ s.key }}</div>
                  @if (s.description) {
                    <div class="muted small clamp">{{ s.description }}</div>
                  }
                </td>
                <td>{{ purpose(s.purpose) }}</td>
                <td class="mono small">{{ shortUrl(s.url) }} · {{ s.defaultBranch }}</td>
                <td class="mono small">{{ jobPath(s.jobKey) }}</td>
                <td><bo-provenance [actor]="s.updatedBy" [at]="s.updatedAt" /></td>
              </tr>
            }
          </tbody>
        </nz-table>
      }

      <bo-side-panel
        [open]="!!draft()"
        [title]="editing() ? draft()!.name || draft()!.key : text.newTitle"
        (closed)="close()"
      >
        @if (draft(); as d) {
          <form nz-form nzLayout="vertical" (ngSubmit)="save()">
            <nz-form-item>
              <nz-form-label nzRequired nzFor="src-key" i18n="@@library.sources.form.key"
                >Key</nz-form-label
              >
              <nz-form-control
                nzExtra="Steps use it; the job clones the source into /workspace/lib-&lt;key&gt;. Cannot change later."
                i18n-nzExtra="@@library.sources.form.keyHint"
              >
                <input
                  nz-input
                  id="src-key"
                  name="key"
                  class="mono"
                  [ngModel]="d.key"
                  (ngModelChange)="patch({ key: $event })"
                  [readOnly]="editing() || !canManage()"
                  [pattern]="keyPattern"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzRequired nzFor="src-name" i18n="@@library.sources.form.name"
                >Name</nz-form-label
              >
              <nz-form-control>
                <input
                  nz-input
                  id="src-name"
                  name="name"
                  [ngModel]="d.name"
                  (ngModelChange)="patch({ name: $event })"
                  [readOnly]="!canManage()"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzRequired nzFor="src-url" i18n="@@library.sources.form.url"
                >Git URL</nz-form-label
              >
              <nz-form-control
                nzExtra="Private repositories need a read credential on the runners (see the manual)."
                i18n-nzExtra="@@library.sources.form.urlHint"
              >
                <input
                  nz-input
                  id="src-url"
                  name="url"
                  class="mono"
                  [placeholder]="examples.url"
                  [ngModel]="d.url"
                  (ngModelChange)="patch({ url: $event })"
                  [readOnly]="!canManage()"
                />
              </nz-form-control>
            </nz-form-item>
            <div class="grid">
              <nz-form-item>
                <nz-form-label nzFor="src-branch" i18n="@@library.sources.form.branch"
                  >Branch</nz-form-label
                >
                <nz-form-control>
                  <input
                    nz-input
                    id="src-branch"
                    name="branch"
                    class="mono"
                    [placeholder]="examples.branch"
                    [ngModel]="d.defaultBranch"
                    (ngModelChange)="patch({ defaultBranch: $event })"
                    [readOnly]="!canManage()"
                  />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="src-purpose" i18n="@@library.sources.form.purpose"
                  >Purpose</nz-form-label
                >
                <nz-form-control>
                  <nz-select
                    nzId="src-purpose"
                    name="purpose"
                    [ngModel]="d.purpose"
                    (ngModelChange)="patch({ purpose: $event })"
                    [nzDisabled]="!canManage()"
                  >
                    @for (p of purposes; track p) {
                      <nz-option [nzValue]="p" [nzLabel]="purpose(p)" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
            </div>
            <nz-form-item>
              <nz-form-label nzFor="src-desc" i18n="@@library.sources.form.description"
                >Description</nz-form-label
              >
              <nz-form-control
                nzExtra="Shown to the agent next to the source: say what to follow or reuse."
                i18n-nzExtra="@@library.sources.form.descriptionHint"
              >
                <textarea
                  nz-input
                  id="src-desc"
                  name="description"
                  [nzAutosize]="{ minRows: 2, maxRows: 6 }"
                  [ngModel]="d.description"
                  (ngModelChange)="patch({ description: $event })"
                  [readOnly]="!canManage()"
                ></textarea>
              </nz-form-control>
            </nz-form-item>
            @for (p of problems(); track p) {
              <p class="problem">{{ p }}</p>
            }
            @if (canManage()) {
              <div class="actions">
                <button
                  nz-button
                  nzType="primary"
                  type="submit"
                  [nzLoading]="saving()"
                  [disabled]="problems().length > 0"
                  i18n="@@library.sources.form.save"
                >
                  Save
                </button>
                @if (editing(); as s) {
                  <button nz-button nzDanger type="button" (click)="remove(s)" i18n="@@library.sources.form.delete">
                    Delete
                  </button>
                }
              </div>
            }
          </form>
        }
      </bo-side-panel>
    </div>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .row {
        cursor: pointer;
      }
      .name {
        font-weight: 500;
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .clamp {
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 12px;
      }
      .actions {
        display: flex;
        gap: 8px;
      }
      .problem {
        color: var(--bo-danger, #c0392b);
        font-size: 12px;
        margin: 0 0 8px;
      }
    `,
  ],
})
export class LibrarySourcesPageComponent {
  private readonly api = inject(LibrarySourcesApi);
  private readonly identity = inject(IdentityService);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);

  readonly items = signal<LibrarySource[]>([]);
  readonly loaded = signal(false);
  readonly draft = signal<LibrarySourceInput | null>(null);
  readonly editing = signal<LibrarySource | null>(null);
  readonly saving = signal(false);
  readonly canManage = computed(() => this.identity.can('library.manage'));
  readonly problems = computed(() => {
    const d = this.draft();
    return d ? sourceProblems(d) : [];
  });

  readonly purposes = REPOSITORY_PURPOSES;
  readonly keyPattern = SOURCE_KEY_PATTERN.source;
  readonly purpose = purposeLabel;
  readonly shortUrl = shortRepoUrl;
  readonly text = { newTitle: $localize`:@@library.sources.form.newTitle:New source` };
  /** Example values in placeholders: code and data, the same in every language. */
  readonly examples = { url: 'https://gitlab.example.com/acme/standards.git', branch: 'main' }; // i18n-ignore

  jobPath(jobKey: string): string {
    return `/workspace/${jobKey}`; // i18n-ignore
  }

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@library.sources.crumb.library:Library`, link: '/library/templates' },
      { label: $localize`:@@library.sources.crumb.sources:Sources` },
    ]);
    this.load();
  }

  load(): void {
    this.api.list().subscribe({
      next: (list) => {
        this.items.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  create(): void {
    this.editing.set(null);
    this.draft.set(emptySource());
  }

  edit(s: LibrarySource): void {
    this.editing.set(s);
    this.draft.set(sourceInputOf(s));
  }

  close(): void {
    this.draft.set(null);
    this.editing.set(null);
  }

  patch(change: Partial<LibrarySourceInput>): void {
    const d = this.draft();
    if (d) this.draft.set({ ...d, ...change });
  }

  save(): void {
    const d = this.draft();
    if (!d || this.problems().length) return;
    this.saving.set(true);
    const existing = this.editing();
    const req = existing ? this.api.update(existing.id, d) : this.api.create(d);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.message.success($localize`:@@library.sources.saved:Source saved`);
        this.close();
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  async remove(s: LibrarySource): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@library.sources.deleteTitle:Delete source "${s.name}:name:"?`,
      $localize`:@@library.sources.deleteContent:Steps that attach it fail validation and their workflows are kept as drafts until the source is removed from them.`,
      $localize`:@@library.sources.deleteConfirm:Delete source`,
    );
    if (!ok) return;
    this.api.delete(s.id).subscribe(() => {
      this.close();
      this.load();
    });
  }
}

import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { LibrarySourcesApi } from '../../../core/api/library-sources.service';
import { ProjectWorkspacesApi } from '../../../core/api/project-workspaces.service';
import type {
  LibrarySource,
  ProjectRepository,
  ProjectWorkspace,
  ProjectWorkspaceInput,
} from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { purposeLabel } from '../../../core/ui/repositories';
import { sourceLabels, workspaceColor, workspaceProblems } from '../../../core/ui/workspaces';

const EMPTY: ProjectWorkspaceInput = { key: '', name: '', description: '', sources: [], setupCommand: null, roles: [] };

/**
 * Project tab Workspaces (ADR 0044): the run workspaces of the project, shared by all its workflows. A workspace says
 * which sources are cloned into it, how it is set up and which agent roles its steps may continue; steps of any
 * workflow pick one by key in their section Workspace, and the designer draws it around those steps.
 */
@Component({
  selector: 'app-project-workspaces-panel',
  imports: [
    FormsModule,
    NzButtonModule,
    NzCheckboxModule,
    NzFormModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    EmptyComponent,
    HelpLinkComponent,
    ProvenanceComponent,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pwp">
      <div class="bar">
        <p class="muted intro" i18n="@@projects.workspaces.intro">
          Steps of a run that pick the same workspace share one directory: clones, uncommitted work, installed
          dependencies and the agents' sessions per role. Every workflow of the project can use these workspaces.
        </p>
        @if (canManage()) {
          <button nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <span i18n="@@projects.workspaces.add">Add workspace</span>
          </button>
        }
      </div>

      @if (loaded() && !items().length) {
        <bo-empty
          title="No workspaces yet"
          i18n-title="@@projects.workspaces.empty.title"
          detail="Every coding step starts from a fresh directory and a new agent session. Add a workspace to let steps share clones, setup and sessions."
          i18n-detail="@@projects.workspaces.empty.detail"
        />
      }
      <div class="cards">
        @for (w of items(); track w.id; let i = $index) {
          <button type="button" class="card" [style.--ws]="color(i)" (click)="edit(w)">
            <div class="head">
              <span class="dot" aria-hidden="true"></span>
              <span class="name">{{ w.name || w.key }}</span>
              <span class="mono muted">{{ w.key }}</span>
            </div>
            @if (w.description) {
              <div class="muted small">{{ w.description }}</div>
            }
            <div class="small">
              <span class="label" i18n="@@projects.workspaces.card.sources">Sources:</span>
              <span class="mono">{{ sources(w) || '—' }}</span>
            </div>
            @if (w.setupCommand) {
              <div class="small">
                <span class="label" i18n="@@projects.workspaces.card.setup">Setup:</span>
                <span class="mono">{{ w.setupCommand }}</span>
              </div>
            }
            <div class="small">
              <span class="label" i18n="@@projects.workspaces.card.roles">Agent roles:</span>
              <span class="mono">{{ roles(w) }}</span>
            </div>
            <bo-provenance class="small" [actor]="w.updatedBy" [at]="w.updatedAt" />
          </button>
        }
      </div>
      <bo-help-link topic="workspaces" variant="text"
        ><span i18n="@@projects.workspaces.guide">How workspaces work</span></bo-help-link
      >

      <bo-side-panel [open]="!!draft()" [title]="editing() ? editing()!.name || editing()!.key : text.newTitle" (closed)="close()">
        @if (draft(); as d) {
          <form nz-form nzLayout="vertical" (ngSubmit)="save()">
            <nz-form-item>
              <nz-form-label nzRequired nzFor="ws-key" i18n="@@projects.workspaces.form.key">Key</nz-form-label>
              <nz-form-control
                nzExtra="Steps pick the workspace by it; it cannot change later."
                i18n-nzExtra="@@projects.workspaces.form.keyHint"
              >
                <input
                  nz-input
                  id="ws-key"
                  name="key"
                  class="mono"
                  [ngModel]="d.key"
                  (ngModelChange)="patch({ key: $event })"
                  [readOnly]="!!editing() || !canManage()"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzFor="ws-name" i18n="@@projects.workspaces.form.name">Name</nz-form-label>
              <nz-form-control
                nzExtra="Shown on the canvas around the steps of the workspace."
                i18n-nzExtra="@@projects.workspaces.form.nameHint"
              >
                <input nz-input id="ws-name" name="name" [ngModel]="d.name" (ngModelChange)="patch({ name: $event })" [readOnly]="!canManage()" />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzFor="ws-desc" i18n="@@projects.workspaces.form.description">Description</nz-form-label>
              <nz-form-control>
                <textarea
                  nz-input
                  id="ws-desc"
                  name="description"
                  [nzAutosize]="{ minRows: 1, maxRows: 4 }"
                  [ngModel]="d.description"
                  (ngModelChange)="patch({ description: $event })"
                  [readOnly]="!canManage()"
                ></textarea>
              </nz-form-control>
            </nz-form-item>

            <div class="sub" i18n="@@projects.workspaces.form.sources">Sources cloned into it</div>
            @if (!repositories().length && !librarySources().length) {
              <p class="muted small" i18n="@@projects.workspaces.form.noSources">
                The project has no repositories and the library no sources.
              </p>
            }
            <div class="checks">
              @for (r of repositories(); track r.key) {
                <label nz-checkbox [ngModel]="has(r.key, false)" (ngModelChange)="toggleSource(r.key, false, $event)" [ngModelOptions]="{ standalone: true }" [nzDisabled]="!canManage()">
                  <span class="mono">{{ r.key }}</span>
                  @if (r.purpose) {
                    <span class="chip">{{ purpose(r.purpose) }}</span>
                  }
                  @if (r.primary) {
                    <span class="chip" i18n="@@projects.workspaces.form.primary">primary</span>
                  }
                </label>
              }
              @for (s of librarySources(); track s.id) {
                <label nz-checkbox [ngModel]="has(s.key, true)" (ngModelChange)="toggleSource(s.key, true, $event)" [ngModelOptions]="{ standalone: true }" [nzDisabled]="!canManage()">
                  <span>{{ s.name }}</span>
                  <span class="mono muted small">{{ s.jobKey }}</span>
                  <span class="chip" i18n="@@projects.workspaces.form.library">library</span>
                </label>
              }
            </div>
            <p class="muted small" i18n="@@projects.workspaces.form.sourcesHint">
              Steps of the workspace without repositories of their own work on these sources: the project's primary
              repository among them gets the step's push access, the others are read only.
            </p>

            <nz-form-item>
              <nz-form-label nzFor="ws-setup" i18n="@@projects.workspaces.form.setup">Setup command</nz-form-label>
              <nz-form-control
                nzExtra="Runs once per run, before the first step of the workspace (again only when you change it)."
                i18n-nzExtra="@@projects.workspaces.form.setupHint"
              >
                <input
                  nz-input
                  id="ws-setup"
                  name="setup"
                  class="mono"
                  [placeholder]="examples.setup"
                  [ngModel]="d.setupCommand ?? ''"
                  (ngModelChange)="patch({ setupCommand: $event || null })"
                  [readOnly]="!canManage()"
                />
              </nz-form-control>
            </nz-form-item>
            <nz-form-item>
              <nz-form-label nzFor="ws-roles" i18n="@@projects.workspaces.form.roles">Agent roles</nz-form-label>
              <nz-form-control
                nzExtra="Steps with the same role continue one agent session, e.g. implementer and reviewer taking turns. Empty = any role."
                i18n-nzExtra="@@projects.workspaces.form.rolesHint"
              >
                <nz-select
                  nzId="ws-roles"
                  name="roles"
                  nzMode="tags"
                  [nzPlaceHolder]="examples.roles"
                  [ngModel]="d.roles ?? []"
                  (ngModelChange)="patch({ roles: $event })"
                  [nzDisabled]="!canManage()"
                ></nz-select>
              </nz-form-control>
            </nz-form-item>
            @for (p of problems(); track p) {
              <p class="problem">{{ p }}</p>
            }
            @if (canManage()) {
              <div class="actions">
                <button nz-button nzType="primary" type="submit" [nzLoading]="saving()" [disabled]="problems().length > 0" i18n="@@projects.workspaces.form.save">
                  Save
                </button>
                @if (editing(); as w) {
                  <button nz-button nzDanger type="button" (click)="remove(w)" i18n="@@projects.workspaces.form.delete">Delete</button>
                }
              </div>
            }
          </form>
        }
      </bo-side-panel>
    </div>
  `,
  styles: [
    `
      .pwp {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .bar {
        display: flex;
        align-items: flex-start;
        gap: 16px;
      }
      .intro {
        flex: 1;
        margin: 0;
      }
      .cards {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
        gap: 12px;
      }
      .card {
        all: unset;
        box-sizing: border-box;
        cursor: pointer;
        display: flex;
        flex-direction: column;
        gap: 4px;
        padding: 10px 12px;
        border: 1px solid var(--bo-line);
        border-left: 4px solid var(--ws);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface);
      }
      .card:hover,
      .card:focus-visible {
        border-color: var(--ws);
      }
      .head {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .dot {
        width: 10px;
        height: 10px;
        border-radius: 3px;
        background: var(--ws);
      }
      .name {
        font-weight: 500;
      }
      .label {
        color: var(--bo-ink-3);
        margin-right: 4px;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
      }
      .sub {
        font-size: 13px;
        font-weight: 500;
        margin-bottom: 6px;
      }
      .checks {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .chip {
        font-size: 11px;
        margin-left: 6px;
        padding: 0 6px;
        border-radius: 999px;
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        color: var(--bo-ink-2);
      }
      .problem {
        color: var(--bo-blocked-ink);
        font-size: 12px;
        margin: 0 0 8px;
      }
      .actions {
        display: flex;
        gap: 8px;
      }
    `,
  ],
})
export class ProjectWorkspacesPanelComponent {
  private readonly api = inject(ProjectWorkspacesApi);
  private readonly librarySourcesApi = inject(LibrarySourcesApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);

  readonly projectId = input.required<string>();
  readonly repositories = input<readonly ProjectRepository[]>([]);
  readonly canManage = input(false);

  readonly items = signal<ProjectWorkspace[]>([]);
  readonly loaded = signal(false);
  readonly librarySources = signal<LibrarySource[]>([]);
  readonly draft = signal<ProjectWorkspaceInput | null>(null);
  readonly editing = signal<ProjectWorkspace | null>(null);
  readonly saving = signal(false);
  readonly problems = computed(() => {
    const d = this.draft();
    if (!d) return [];
    const others = this.items().filter((w) => w.id !== this.editing()?.id);
    return workspaceProblems(d, others);
  });

  readonly color = workspaceColor;
  readonly purpose = purposeLabel;
  readonly anyRole = $localize`:@@projects.workspaces.anyRole:any`;
  readonly text = { newTitle: $localize`:@@projects.workspaces.form.newTitle:New workspace` };
  /** Example values in placeholders: code, the same in every language. */
  readonly examples = { setup: 'npm ci', roles: 'implementer, reviewer' }; // i18n-ignore

  constructor() {
    effect(() => {
      const id = this.projectId();
      untracked(() => this.load(id));
    });
    this.librarySourcesApi.list().subscribe({ next: (s) => this.librarySources.set(s), error: () => undefined });
  }

  roles(w: ProjectWorkspace): string {
    return w.roles?.length ? w.roles.join(', ') : this.anyRole;
  }

  sources(w: ProjectWorkspace): string {
    return sourceLabels(w).join(', ');
  }

  has(key: string, library: boolean): boolean {
    return (this.draft()?.sources ?? []).some((s) => s.key === key && !!s.library === library);
  }

  create(): void {
    this.editing.set(null);
    this.draft.set({ ...EMPTY, key: this.items().some((w) => w.key === 'main') ? '' : 'main' });
  }

  edit(w: ProjectWorkspace): void {
    this.editing.set(w);
    this.draft.set({
      key: w.key,
      name: w.name,
      description: w.description ?? '',
      sources: [...(w.sources ?? [])],
      setupCommand: w.setupCommand ?? null,
      roles: [...(w.roles ?? [])],
    });
  }

  close(): void {
    this.draft.set(null);
    this.editing.set(null);
  }

  patch(change: Partial<ProjectWorkspaceInput>): void {
    const d = this.draft();
    if (d) this.draft.set({ ...d, ...change });
  }

  toggleSource(key: string, library: boolean, on: boolean): void {
    const rest = (this.draft()?.sources ?? []).filter((s) => !(s.key === key && !!s.library === library));
    this.patch({ sources: on ? [...rest, { key, library }] : rest });
  }

  save(): void {
    const d = this.draft();
    if (!d || this.problems().length) return;
    this.saving.set(true);
    const existing = this.editing();
    const req = existing ? this.api.update(this.projectId(), existing.id, d) : this.api.create(this.projectId(), d);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.message.success($localize`:@@projects.workspaces.saved:Workspace saved`);
        this.close();
        this.load(this.projectId());
      },
      error: () => this.saving.set(false),
    });
  }

  async remove(w: ProjectWorkspace): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@projects.workspaces.deleteTitle:Delete workspace "${w.name || w.key}:name:"?`,
      $localize`:@@projects.workspaces.deleteContent:Steps of workflows that pick it fail validation and are kept as drafts until they pick another workspace.`,
      $localize`:@@projects.workspaces.deleteConfirm:Delete workspace`,
    );
    if (!ok) return;
    this.api.delete(this.projectId(), w.id).subscribe(() => {
      this.close();
      this.load(this.projectId());
    });
  }

  private load(projectId: string): void {
    this.api.list(projectId).subscribe({
      next: (list) => {
        this.items.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }
}

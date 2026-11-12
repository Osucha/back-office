import { HelpTipComponent } from '../../../core/ui/help-tip.component';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type { Agent, AgentInput, ModelProfile, Project } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { icu } from '../../../core/i18n/icu';
import { agentAccessText, agentKindDescription, agentKindLabel } from '../../../core/ui/agents';
import {
  AgentEditorPanelComponent,
  canEditScope,
  customizeAgentForProject,
} from '../../library/public-api';
import { importableAgents, projectAgentRows } from '../data/project-agents';

/**
 * Project tab Agents (ADR 0060): the agents the project's steps can use (the global ones and the project's own), a new
 * project agent, or a project copy of a global agent ("Import from library") that can then be changed and replaces the
 * global agent of its key in this project. The editor is the library's agent editor, locked to this project.
 */
@Component({
  selector: 'app-project-agents-panel',
  imports: [
    HelpTipComponent,
    FormsModule,
    NzButtonModule,
    NzCardModule,
    NzIconModule,
    NzModalModule,
    NzSelectModule,
    NzTableModule,
    NzTooltipModule,
    AgentEditorPanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-card [nzTitle]="agentsTitle" [nzExtra]="actions">
      <ng-template #agentsTitle
        ><span i18n="@@projects.agents.title">Agents</span>
        <bo-help-tip [text]="agentsHelp" style="margin-left: 6px"
      /></ng-template>
      <ng-template #agentsHelp
        ><span i18n="@@projects.agents.intro">
          Who works on the steps of this project: the global agents of the library and the project's
          own. A project agent with the key of a global one replaces it in this project.
        </span></ng-template
      >
      @if (!loaded()) {
        <p class="muted" i18n="@@projects.agents.loading">Loading…</p>
      } @else if (!rows().length) {
        <p class="muted" i18n="@@projects.agents.empty">No agents yet.</p>
      } @else {
        <nz-table [nzData]="rows()" [nzShowPagination]="false" nzSize="small">
          <thead>
            <tr>
              <th i18n="@@projects.agents.col.agent">Agent</th>
              <th i18n="@@projects.agents.col.scope">Scope</th>
              <th i18n="@@projects.agents.col.kind">Kind</th>
              <th i18n="@@projects.agents.col.model">Model</th>
              <th i18n="@@projects.agents.col.access">Access</th>
              <th i18n="@@projects.agents.col.environment">Environment</th>
            </tr>
          </thead>
          <tbody>
            @for (r of rows(); track r.agent.id ?? r.agent.key) {
              <tr
                class="clickable"
                [class.replaced]="r.replaced"
                tabindex="0"
                (click)="edit(r.agent)"
                (keydown.enter)="edit(r.agent)"
                [attr.data-agent]="r.agent.key"
              >
                <td>
                  <div>{{ r.agent.name }}</div>
                  <div class="mono muted">{{ r.agent.key }}</div>
                </td>
                <td>
                  @if (r.own) {
                    <span
                      class="chip accent"
                      data-scope="PROJECT"
                      i18n="@@projects.agents.scope.project"
                      >This project</span
                    >
                    @if (r.replacesGlobal) {
                      <div class="muted small" i18n="@@projects.agents.replacesGlobal">
                        replaces the global agent
                      </div>
                    }
                  } @else {
                    <span class="chip" data-scope="GLOBAL" i18n="@@projects.agents.scope.global"
                      >Global</span
                    >
                    @if (r.replaced) {
                      <div class="muted small" i18n="@@projects.agents.replaced">
                        replaced in this project
                      </div>
                    }
                  }
                </td>
                <td>
                  <span nz-tooltip [nzTooltipTitle]="kindDescription(r.agent.harness)">{{
                    kindLabel(r.agent.harness)
                  }}</span>
                </td>
                <td>{{ modelName(r.agent.modelProfileId) }}</td>
                <td data-access>{{ r.agent.harness ? accessText(r.agent.access) : '—' }}</td>
                <td class="muted">{{ environmentLabel(r.agent) }}</td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </nz-card>

    <ng-template #actions>
      @if (canManage()) {
        <button nz-button type="button" (click)="importOpen.set(true)" data-import>
          <nz-icon nzType="import" />
          <span i18n="@@projects.agents.import">Import from library</span>
        </button>
        <button nz-button nzType="primary" type="button" (click)="create()" data-create>
          <nz-icon nzType="plus" />
          <span i18n="@@projects.agents.create">Create agent</span>
        </button>
      }
    </ng-template>

    <nz-modal
      [nzVisible]="importOpen()"
      nzTitle="Import an agent from the library"
      i18n-nzTitle="@@projects.agents.import.title"
      nzOkText="Import"
      i18n-nzOkText="@@projects.agents.import.ok"
      nzCancelText="Cancel"
      i18n-nzCancelText="@@projects.agents.import.cancel"
      [nzOkDisabled]="!importKey()"
      [nzOkLoading]="importing()"
      (nzOnOk)="importAgent()"
      (nzOnCancel)="importOpen.set(false)"
    >
      <ng-container *nzModalContent>
        <p i18n="@@projects.agents.import.intro">
          Creates a copy of the global agent in this project, with the same key, and opens it for
          editing. The copy replaces the global agent in this project's steps; other projects keep
          the global one.
        </p>
        @if (importable().length) {
          <nz-select
            class="full"
            nzShowSearch
            nzPlaceHolder="Choose a global agent"
            i18n-nzPlaceHolder="@@projects.agents.import.pick"
            [ngModel]="importKey()"
            (ngModelChange)="importKey.set($event)"
            aria-label="Global agent to import"
            i18n-aria-label="@@projects.agents.import.pickLabel"
          >
            @for (a of importable(); track a.key) {
              <nz-option [nzValue]="a.key" [nzLabel]="a.name + ' (' + kindLabel(a.harness) + ')'" />
            }
          </nz-select>
        } @else {
          <p class="muted" i18n="@@projects.agents.import.none">
            The project already has a copy of every global agent.
          </p>
        }
      </ng-container>
    </nz-modal>

    <app-agent-editor-panel
      [open]="editorOpen()"
      [agent]="editing()"
      [prefill]="prefill()"
      [projects]="projectList()"
      [profiles]="profiles()"
      [projectId]="projectId()"
      [lockProject]="true"
      [shadows]="editingShadows()"
      (saved)="onSaved()"
      (closed)="closeEditor()"
    />
  `,
  styles: [
    `
      .clickable {
        cursor: pointer;
      }
      .replaced td {
        opacity: 0.6;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .small {
        font-size: 12px;
      }
      .full {
        width: 100%;
      }
    `,
  ],
})
export class ProjectAgentsPanelComponent {
  private readonly api = inject(TaskLibraryApi);
  private readonly profilesApi = inject(ModelProfilesApi);
  private readonly message = inject(NzMessageService);
  private readonly identity = inject(IdentityService);

  readonly projectId = input.required<string>();
  /** The project (the editor's scope select shows it). */
  readonly project = input<Project | null>(null);

  readonly agents = signal<Agent[]>([]);
  readonly loaded = signal(false);
  readonly profiles = signal<ModelProfile[]>([]);
  /** The agent open in the editor: an id, 'new', or null when closed. */
  readonly editingId = signal<string | null>(null);
  readonly prefill = signal<AgentInput | null>(null);
  readonly importOpen = signal(false);
  readonly importKey = signal<string | null>(null);
  readonly importing = signal(false);

  readonly kindLabel = agentKindLabel;
  readonly kindDescription = agentKindDescription;
  readonly accessText = agentAccessText;

  readonly rows = computed(() => projectAgentRows(this.agents(), this.projectId()));
  readonly importable = computed(() => importableAgents(this.agents(), this.projectId()));
  readonly projectList = computed(() => {
    const p = this.project();
    return p ? [p] : [];
  });
  /** The user may create and change agents of this project (`library.project.manage`). */
  readonly canManage = computed(() =>
    canEditScope(this.identity, { type: 'PROJECT', projectId: this.projectId() }),
  );
  readonly editing = computed(() => {
    const id = this.editingId();
    return id && id !== 'new' ? (this.agents().find((a) => a.id === id) ?? null) : null;
  });
  readonly editorOpen = computed(() => this.editingId() === 'new' || !!this.editing());
  readonly editingShadows = computed(
    () => this.rows().find((r) => r.agent === this.editing())?.replacesGlobal ?? false,
  );

  constructor() {
    this.profilesApi.listCallable().subscribe({
      next: (p) => this.profiles.set(p),
      error: () => undefined,
    });
    effect(() => {
      const id = this.projectId();
      untracked(() => this.load(id));
    });
  }

  load(projectId = this.projectId()): void {
    this.api.listAgents(projectId).subscribe({
      next: (list) => {
        this.agents.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  modelName(id: string | null | undefined): string {
    if (!id) return $localize`:@@projects.agents.model.default:Default for the step`;
    return (
      this.profiles().find((p) => p.id === id)?.displayName ??
      $localize`:@@projects.agents.model.unknown:Unknown model profile`
    );
  }

  /** "2 rules · 3 skills". */
  environmentLabel(a: Agent): string {
    const rules = icu(
      $localize`:@@projects.agents.rulesCount:{count, plural, one {# rule} other {# rules}}`,
      { count: a.rules?.length ?? 0 },
    );
    const skills = icu(
      $localize`:@@projects.agents.skillsCount:{count, plural, one {# skill} other {# skills}}`,
      { count: a.skills?.length ?? 0 },
    );
    return `${rules} · ${skills}`;
  }

  create(): void {
    this.prefill.set(null);
    this.editingId.set('new');
  }

  edit(a: Agent): void {
    if (!a.id) return;
    this.prefill.set(null);
    this.editingId.set(a.id);
  }

  closeEditor(): void {
    this.prefill.set(null);
    this.editingId.set(null);
  }

  onSaved(): void {
    this.closeEditor();
    this.load();
  }

  /** Creates the project copy of the chosen global agent and opens it for editing. */
  importAgent(): void {
    const source = this.importable().find((a) => a.key === this.importKey());
    if (!source) return;
    this.importing.set(true);
    this.api.createAgent(customizeAgentForProject(source, this.projectId())).subscribe({
      next: (created) => {
        this.importing.set(false);
        this.importOpen.set(false);
        this.importKey.set(null);
        this.message.success(
          $localize`:@@projects.agents.imported:Agent "${created.name}:name:" imported into the project`,
        );
        this.agents.update((list) => [...list, created]);
        this.edit(created);
      },
      error: () => this.importing.set(false),
    });
  }
}

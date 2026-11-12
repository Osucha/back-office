import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type {
  Agent,
  AgentAccessMode,
  AgentInput,
  AgentMcpServer,
  ModelProfile,
  Project,
  Rule,
  Scope,
  Skill,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import {
  agentAccessModeLabel,
  agentKindDescription,
  agentKindLabel,
} from '../../../core/ui/agents';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import {
  ACCESS_MODES,
  AGENT_KINDS,
  type AgentKindValue,
  accessMode,
  accessProblem,
  addMcpServer,
  agentDraft,
  agentPayload,
  agentSavable,
  emptyAgent,
  harnessOf,
  kindValue,
  mcpServerProblem,
  patchMcpServer,
  removeMcpServer,
  withAccessMode,
  withAccessPaths,
} from '../data/agent-form';
import { canCreateInLibrary, canEditScope, editableProjects } from '../data/library-access';

/** Rules or skills an item of this scope may list (global plus the scope's project), by name. */
function visibleOptions(
  items: readonly { key?: string | null; name?: string | null; scope?: Scope | null }[],
  scope: Scope,
): Array<{ key: string; label: string }> {
  const projectId = scope.type === 'PROJECT' ? scope.projectId : null;
  const byKey = new Map<string, { key: string; label: string }>();
  for (const i of items) {
    const key = i.key ?? '';
    if (!key) continue;
    if (i.scope?.type === 'PROJECT' && i.scope.projectId !== projectId) continue;
    byKey.set(key, { key, label: i.name || key });
  }
  return [...byKey.values()];
}

/**
 * The agent editor (ADR 0056/0057/0060) in a side panel, shared by the library page and the project tab Agents: name,
 * kind, model, instructions, rules, skills, MCP servers and access (coding agents), scope and tags. Built-in agents and
 * agents of a scope the user cannot change are read-only. With `lockProject` the editor creates agents of the
 * `projectId` only (project tab).
 */
@Component({
  selector: 'app-agent-editor-panel',
  imports: [
    ReadonlyFormDirective,
    FormsModule,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    NzRadioModule,
    NzFormModule,
    ActionBarComponent,
    ProvenanceComponent,
    SidePanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      [open]="open()"
      [title]="agent() ? agent()!.name : text.newTitle"
      [subtitle]="agent() ? (agent()!.description ?? '') : text.newSubtitle"
      [defaultWidth]="640"
      storageKey="backoffice.panelWidth.agent"
      [bodyLabel]="canEditDraft() ? null : text.details"
      initialFocus="#ag-name"
      (closed)="requestClose()"
    >
      @if (agent()?.builtIn) {
        <span boPanelHeader class="chip outline"
          ><nz-icon nzType="lock" /> <span i18n="@@library.agents.builtIn">built-in</span></span
        >
      }
      @if (agent()) {
        <bo-provenance
          boPanelHeader
          verb="updated by"
          i18n-verb="@@library.agents.updatedBy"
          [actor]="agent()!.updatedBy"
          [at]="agent()!.updatedAt"
        />
      }
      @if (agent()?.builtIn) {
        <p class="field-help builtin-note" data-builtin-note>
          <span i18n="@@library.agents.builtInNote"
            >Built-in agents cannot be changed. To use another model in a project, customize the
            agent for that project.</span
          >
        </p>
      }
      @if (shadowNote()) {
        <p class="field-help builtin-note" data-shadow-note>{{ shadowNote() }}</p>
      }
      <form
        nz-form
        nzLayout="vertical"
        [boReadonly]="!canEditDraft()"
        (submit)="$event.preventDefault()"
      >
        <nz-form-item>
          <nz-form-label nzRequired nzFor="ag-name" i18n="@@library.agents.name"
            >Name</nz-form-label
          >
          <nz-form-control>
            <input
              nz-input
              id="ag-name"
              name="name"
              [ngModel]="draft().name"
              (ngModelChange)="patch({ name: $event })"
              placeholder="Cheap coder"
              i18n-placeholder="@@library.agents.name.placeholder"
            />
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzFor="ag-desc" i18n="@@library.agents.description"
            >Description</nz-form-label
          >
          <nz-form-control>
            <input
              nz-input
              id="ag-desc"
              name="description"
              [ngModel]="draft().description ?? ''"
              (ngModelChange)="patch({ description: $event })"
            />
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label i18n="@@library.agents.kind">Kind</nz-form-label>
          <nz-form-control>
            <nz-radio-group
              name="kind"
              class="kinds"
              [ngModel]="kind()"
              (ngModelChange)="setKind($event)"
            >
              @for (k of kinds; track k.value) {
                <label nz-radio [nzValue]="k.value" [attr.data-kind]="k.value">
                  <span class="kind-label">{{ k.label }}</span>
                  <span class="kind-help">{{ k.help }}</span>
                </label>
              }
            </nz-radio-group>
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label
            [nzTooltipTitle]="text.modelHelp"
            nzFor="ag-model"
            i18n="@@library.agents.model"
            >Model</nz-form-label
          >
          <nz-form-control>
            <nz-select
              id="ag-model"
              name="modelProfileId"
              nzAllowClear
              nzShowSearch
              nzPlaceHolder="Default for the step"
              i18n-nzPlaceHolder="@@library.agents.model.placeholder"
              [ngModel]="draft().modelProfileId ?? null"
              (ngModelChange)="patch({ modelProfileId: $event ?? null })"
            >
              @for (p of profiles(); track p.id) {
                <nz-option
                  [nzValue]="p.id"
                  [nzLabel]="p.displayName + ' (' + p.ref.modelId + ')'"
                />
              }
            </nz-select>
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label
            [nzTooltipTitle]="text.instructionsHelp"
            nzFor="ag-instructions"
            i18n="@@library.agents.instructions"
            >Instructions</nz-form-label
          >
          <nz-form-control>
            <textarea
              nz-input
              id="ag-instructions"
              name="instructions"
              class="mono-textarea"
              [ngModel]="draft().instructions ?? ''"
              (ngModelChange)="patch({ instructions: $event })"
              [nzAutosize]="{ minRows: 4, maxRows: 16 }"
              placeholder="You are a careful reviewer. Explain every finding with the file and line."
              i18n-placeholder="@@library.agents.instructions.placeholder"
            ></textarea>
          </nz-form-control>
        </nz-form-item>
        <div class="grid">
          <nz-form-item>
            <nz-form-label
              nzTooltipTitle="Always in the agent's context, after the automatic rules."
              i18n-nzTooltipTitle="@@library.agents.rules.help"
              nzFor="ag-rules"
              i18n="@@library.agents.rules"
              >Rules</nz-form-label
            >
            <nz-form-control>
              <nz-select
                id="ag-rules"
                name="rules"
                nzMode="multiple"
                nzShowSearch
                nzPlaceHolder="none"
                i18n-nzPlaceHolder="@@library.agents.none"
                [ngModel]="draft().rules ?? []"
                (ngModelChange)="patch({ rules: $event })"
              >
                @for (r of ruleOptions(); track r.key) {
                  <nz-option [nzValue]="r.key" [nzLabel]="r.label" />
                }
              </nz-select>
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label
              nzTooltipTitle="Knowledge the agent loads when it needs it."
              i18n-nzTooltipTitle="@@library.agents.skills.help"
              nzFor="ag-skills"
              i18n="@@library.agents.skills"
              >Skills</nz-form-label
            >
            <nz-form-control>
              <nz-select
                id="ag-skills"
                name="skills"
                nzMode="multiple"
                nzShowSearch
                nzPlaceHolder="none"
                i18n-nzPlaceHolder="@@library.agents.none"
                [ngModel]="draft().skills ?? []"
                (ngModelChange)="patch({ skills: $event })"
              >
                @for (s of skillOptions(); track s.key) {
                  <nz-option [nzValue]="s.key" [nzLabel]="s.label" />
                }
              </nz-select>
            </nz-form-control>
          </nz-form-item>
        </div>
        @if (draft().harness) {
          <nz-form-item data-agent-access>
            <nz-form-label i18n="@@library.agents.access">Access</nz-form-label>
            <nz-form-control
              [nzExtra]="text.accessHelp"
              [nzValidateStatus]="accessError() ? 'error' : ''"
              [nzErrorTip]="accessError() ? text.accessPathsRequired : ''"
            >
              <nz-radio-group
                name="accessMode"
                nzSize="small"
                [ngModel]="access()"
                (ngModelChange)="setAccess($event)"
              >
                @for (m of accessModes; track m.value) {
                  <label nz-radio-button [nzValue]="m.value" [attr.data-access]="m.value">{{
                    m.label
                  }}</label>
                }
              </nz-radio-group>
              @if (access() === 'PATHS') {
                <nz-select
                  class="paths"
                  name="accessPaths"
                  nzMode="tags"
                  nzPlaceHolder="docs/**"
                  i18n-nzPlaceHolder="@@library.agents.access.pathsPlaceholder"
                  [ngModel]="draft().access?.paths ?? []"
                  (ngModelChange)="setAccessPaths($event)"
                  aria-label="Paths the agent may change"
                  i18n-aria-label="@@library.agents.access.pathsLabel"
                ></nz-select>
              }
            </nz-form-control>
          </nz-form-item>
        }
        <nz-form-item data-mcp-servers>
          <nz-form-label
            [nzTooltipTitle]="draft().harness ? text.mcpHelp : text.mcpModelAnswer"
            i18n="@@library.agents.mcp"
            >MCP servers</nz-form-label
          >
          <nz-form-control>
            @if (draft().harness) {
              @for (s of draft().mcpServers ?? []; track $index; let i = $index) {
                <div class="mcp-row" [attr.data-mcp-row]="i">
                  <input
                    nz-input
                    class="mono"
                    [name]="'mcp-name-' + i"
                    [ngModel]="s.name"
                    (ngModelChange)="patchMcp(i, { name: $event })"
                    placeholder="docs"
                    i18n-placeholder="@@library.agents.mcp.namePlaceholder"
                    aria-label="Name of the MCP server"
                    i18n-aria-label="@@library.agents.mcp.nameLabel"
                  />
                  <input
                    nz-input
                    class="mono"
                    [name]="'mcp-url-' + i"
                    [ngModel]="s.url"
                    (ngModelChange)="patchMcp(i, { url: $event })"
                    placeholder="https://mcp.example.com/mcp"
                    i18n-placeholder="@@library.agents.mcp.urlPlaceholder"
                    aria-label="URL of the MCP server"
                    i18n-aria-label="@@library.agents.mcp.urlLabel"
                  />
                  @if (canEditDraft()) {
                    <button
                      nz-button
                      nzSize="small"
                      nzDanger
                      type="button"
                      (click)="removeMcp(i)"
                      aria-label="Remove MCP server"
                      i18n-aria-label="@@library.agents.mcp.remove"
                    >
                      <nz-icon nzType="delete" />
                    </button>
                  }
                </div>
              }
              @if (mcpError(); as err) {
                <div class="danger small" data-mcp-error>{{ err }}</div>
              }
              @if (canEditDraft()) {
                <button
                  nz-button
                  nzType="dashed"
                  nzSize="small"
                  type="button"
                  (click)="addMcp()"
                  i18n="@@library.agents.mcp.add"
                >
                  Add MCP server
                </button>
              }
            }
          </nz-form-control>
        </nz-form-item>
        <div class="grid">
          <nz-form-item>
            <nz-form-label i18n="@@library.agents.scope">Scope</nz-form-label>
            <nz-form-control>
              <nz-radio-group
                name="scope"
                [ngModel]="draft().scope.type"
                (ngModelChange)="setScope($event)"
                [disabled]="!!agent() || lockProject()"
              >
                <label
                  nz-radio-button
                  nzValue="GLOBAL"
                  [nzDisabled]="!identity.can('library.manage')"
                  i18n="@@library.agents.scope.globalOption"
                  >Global</label
                >
                <label nz-radio-button nzValue="PROJECT" i18n="@@library.agents.scope.project"
                  >Project</label
                >
              </nz-radio-group>
              @if (draft().scope.type === 'PROJECT') {
                <nz-select
                  name="scopeProject"
                  nzShowSearch
                  nzPlaceHolder="Project"
                  i18n-nzPlaceHolder="@@library.agents.project"
                  [ngModel]="draft().scope.projectId ?? null"
                  (ngModelChange)="patch({ scope: { type: 'PROJECT', projectId: $event } })"
                  [nzDisabled]="!!agent() || lockProject()"
                  style="width: 100%; margin-top: 6px"
                  aria-label="Project of the agent"
                  i18n-aria-label="@@library.agents.projectLabel"
                >
                  @for (p of scopeProjects(); track p.id) {
                    <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' - ' + p.name" />
                  }
                </nz-select>
              }
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="ag-tags" i18n="@@library.agents.tags">Tags</nz-form-label>
            <nz-form-control>
              <nz-select
                id="ag-tags"
                name="tags"
                nzMode="tags"
                [ngModel]="draft().tags ?? []"
                (ngModelChange)="patch({ tags: $event })"
                nzPlaceHolder="budget, review"
                i18n-nzPlaceHolder="@@library.agents.tags.placeholder"
              ></nz-select>
            </nz-form-control>
          </nz-form-item>
        </div>
        @if (agent()?.key || draft().key) {
          <details class="advanced">
            <summary i18n="@@library.agents.advanced">Advanced</summary>
            <p class="field-help key-note" data-agent-key>
              <span i18n="@@library.agents.keyNote">Technical key (MCP, support):</span>
              <span class="mono">{{ agent()?.key ?? draft().key }}</span>
            </p>
          </details>
        }
      </form>
      <div boPanelFooter>
        <bo-action-bar
          [sticky]="false"
          [dirty]="dirty()"
          [saving]="saving()"
          [canSave]="canSave()"
          [showSave]="open() && canEditDraft()"
          [saveLabel]="agent() ? text.save : text.create"
          [cleanLabel]="canEditDraft() ? text.noChanges : text.readOnly"
          (save)="save()"
        >
          @if (showCustomize()) {
            <button nz-button type="button" (click)="customize.emit()" data-customize>
              <nz-icon nzType="copy" />
              <span i18n="@@library.agents.customize">Customize for project</span>
            </button>
          }
          <button nz-button type="button" (click)="requestClose()">
            {{ dirty() ? text.discard : text.close }}
          </button>
        </bo-action-bar>
      </div>
    </bo-side-panel>
  `,
  styles: [
    `
      .grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0 16px;
      }
      .kinds {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .kind-label {
        font-weight: 500;
      }
      .kind-help {
        display: block;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .builtin-note {
        margin: 0 0 12px;
      }
      .paths {
        width: 100%;
        margin-top: 6px;
      }
      .mcp-row {
        display: grid;
        grid-template-columns: 140px minmax(0, 1fr) auto;
        gap: 6px;
        align-items: center;
        margin-bottom: 6px;
      }
      .danger.small {
        font-size: 12px;
        color: var(--bo-blocked-ink);
        margin-bottom: 6px;
      }
      .advanced summary {
        cursor: pointer;
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .key-note {
        margin-top: 8px;
        color: var(--bo-ink-3);
      }
      [boPanelFooter] {
        display: block;
        margin: -13px -20px -12px;
      }
    `,
  ],
})
export class AgentEditorPanelComponent {
  private readonly api = inject(TaskLibraryApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  readonly identity = inject(IdentityService);

  readonly open = input(false);
  /** The agent edited; null = a new one. */
  readonly agent = input<Agent | null>(null);
  /** A prefilled new agent (customize or import); null = blank. */
  readonly prefill = input<AgentInput | null>(null);
  readonly projects = input<readonly Project[]>([]);
  readonly profiles = input<readonly ModelProfile[]>([]);
  /** The project in view (library filter or project tab): new project agents and the rules and skills offered. */
  readonly projectId = input<string | null>(null);
  /** Project tab: new agents belong to `projectId`; the scope cannot change. */
  readonly lockProject = input(false);
  readonly showCustomize = input(false);
  /** ADR 0060 (project tab): the agent is a project copy that replaces the global agent of its key. */
  readonly shadows = input(false);

  readonly saved = output<Agent>();
  readonly closed = output<void>();
  readonly customize = output<void>();

  readonly draft = signal<AgentInput>(emptyAgent());
  private readonly initialJson = signal(JSON.stringify(emptyAgent()));
  readonly saving = signal(false);
  /** ADR 0057: rules and skills of the draft's project (plus the global ones). */
  readonly rules = signal<Rule[]>([]);
  readonly skills = signal<Skill[]>([]);

  readonly text = {
    create: $localize`:@@library.agents.create:Create agent`,
    save: $localize`:@@library.agents.save:Save agent`,
    newTitle: $localize`:@@library.agents.newTitle:New agent`,
    newSubtitle: $localize`:@@library.agents.newSubtitle:Who runs a task: a model answer or a coding agent`,
    details: $localize`:@@library.agents.details:Agent details`,
    noChanges: $localize`:@@library.agents.noChanges:No changes`,
    readOnly: $localize`:@@library.agents.readOnly:Read-only: built-in, or you cannot change agents in this scope`,
    discard: $localize`:@@library.agents.discard:Discard changes`,
    close: $localize`:@@library.agents.close:Close`,
    modelHelp: $localize`:@@library.agents.model.help:Empty: the step's model or the defaults for its kind (project, starting user, platform).`,
    instructionsHelp: $localize`:@@library.agents.instructions.help:The agent's role and way of working, in markdown. It may use ${'{{project.*}}'}:example: and comes after the platform preamble (what Back Office is, honesty, safety).`,
    mcpHelp: $localize`:@@library.agents.mcp.help:Remote MCP servers (HTTP or HTTPS) the agent may use. No credentials: the sandbox network policy decides whether a server is reachable.`,
    mcpModelAnswer: $localize`:@@library.agents.mcp.modelAnswer:Only coding agents use MCP servers; a model answer has no tools.`,
    accessHelp: $localize`:@@library.agents.access.help:What the agent may change in the repositories of its steps. After the agent finished, Back Office reverts every other change and lists it in the step's log.`,
    accessPathsRequired: $localize`:@@library.agents.access.pathsRequired:Name at least one path, e.g. docs/**`,
    shadow: $localize`:@@library.agents.shadowNote:This project copy replaces the global agent with the same key in this project's steps.`,
  };

  readonly kinds = AGENT_KINDS.map((h) => ({
    value: kindValue(h),
    label: agentKindLabel(h),
    help: agentKindDescription(h),
  }));
  readonly accessModes = ACCESS_MODES.map((m) => ({ value: m, label: agentAccessModeLabel(m) }));

  readonly scopeProjects = computed(() => editableProjects(this.identity, this.projects()));
  readonly canEditDraft = computed(() => {
    const a = this.agent();
    if (a) return !a.builtIn && canEditScope(this.identity, a.scope);
    return this.lockProject()
      ? canEditScope(this.identity, { type: 'PROJECT', projectId: this.projectId() })
      : canCreateInLibrary(this.identity);
  });
  readonly kind = computed<AgentKindValue>(() => kindValue(this.draft().harness));
  readonly access = computed<AgentAccessMode>(() => accessMode(this.draft()));
  readonly accessError = computed(() => accessProblem(this.draft()));
  readonly dirty = computed(
    () => this.open() && this.canEditDraft() && JSON.stringify(this.draft()) !== this.initialJson(),
  );
  readonly canSave = computed(() => agentSavable(this.draft()));
  readonly shadowNote = computed(() =>
    this.shadows() && this.draft().scope.type === 'PROJECT' ? this.text.shadow : null,
  );
  /** The project whose rules and skills are loaded: the draft's, else the one in view. */
  private readonly environmentProjectId = computed(() => {
    const scope = this.draft().scope;
    return scope.type === 'PROJECT' ? (scope.projectId ?? null) : this.projectId();
  });
  /** ADR 0057: rules and skills an agent of the draft's scope may list (global plus its project's). */
  readonly ruleOptions = computed(() => visibleOptions(this.rules(), this.draft().scope));
  readonly skillOptions = computed(() => visibleOptions(this.skills(), this.draft().scope));
  /** ADR 0057: what is wrong with the MCP servers of a coding agent, in words. */
  readonly mcpError = computed(() => {
    const d = this.draft();
    const p = d.harness ? mcpServerProblem(d.mcpServers) : null;
    if (!p) return null;
    const n = p.index + 1;
    switch (p.kind) {
      case 'badName':
        return $localize`:@@library.agents.mcp.badName:MCP server ${n}:index:: the name starts with a lowercase letter and uses only lowercase letters, digits and - (at most 40)`;
      case 'duplicate':
        return $localize`:@@library.agents.mcp.duplicate:MCP server name ${p.name}:name: is used twice`;
      case 'badUrl':
        return $localize`:@@library.agents.mcp.badUrl:MCP server ${p.name}:name: needs an http:// or https:// URL`;
    }
  });

  constructor() {
    effect(() => {
      const projectId = this.environmentProjectId();
      untracked(() => this.loadEnvironment(projectId));
    });
    // a new draft whenever another agent (or a new one) is opened
    effect(() => {
      const a = this.agent();
      const prefill = this.prefill();
      this.open();
      untracked(() => {
        const base = this.newAgent();
        const d = a ? agentDraft(a) : (prefill ?? base);
        this.draft.set(d);
        this.initialJson.set(JSON.stringify(a ? d : base));
      });
    });
  }

  /** A blank agent: of the project in view when the editor is locked to it. */
  private newAgent(): AgentInput {
    const blank = emptyAgent();
    return this.lockProject()
      ? { ...blank, scope: { type: 'PROJECT', projectId: this.projectId() } }
      : blank;
  }

  private loadEnvironment(projectId: string | null): void {
    this.api.listRules(projectId).subscribe({
      next: (r) => this.rules.set(r),
      error: () => undefined,
    });
    this.api.listSkills(projectId).subscribe({
      next: (s) => this.skills.set(s),
      error: () => undefined,
    });
  }

  patch(changes: Partial<AgentInput>): void {
    this.draft.update((d) => ({ ...d, ...changes }));
  }

  setKind(value: AgentKindValue): void {
    this.patch({ harness: harnessOf(value) });
  }

  setAccess(mode: AgentAccessMode): void {
    this.draft.update((d) => withAccessMode(d, mode));
  }

  setAccessPaths(paths: string[]): void {
    this.draft.update((d) => withAccessPaths(d, paths));
  }

  setScope(type: 'GLOBAL' | 'PROJECT'): void {
    this.patch({
      scope: {
        type,
        projectId:
          type === 'PROJECT'
            ? (this.draft().scope.projectId ??
              this.projectId() ??
              this.scopeProjects()[0]?.id ??
              null)
            : null,
      },
    });
  }

  addMcp(): void {
    this.patch({ mcpServers: addMcpServer(this.draft().mcpServers) });
  }

  patchMcp(index: number, changes: Partial<AgentMcpServer>): void {
    this.patch({ mcpServers: patchMcpServer(this.draft().mcpServers, index, changes) });
  }

  removeMcp(index: number): void {
    this.patch({ mcpServers: removeMcpServer(this.draft().mcpServers, index) });
  }

  async requestClose(): Promise<void> {
    if (this.dirty()) {
      const ok = await this.confirm.confirm({
        title: $localize`:@@library.agents.discard.title:Discard unsaved changes?`,
        content: $localize`:@@library.agents.discard.content:The changes to this agent have not been saved.`,
        okText: $localize`:@@library.agents.discard:Discard changes`,
        danger: true,
      });
      if (!ok) return;
    }
    this.closed.emit();
  }

  save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    const existing = this.agent();
    // the key is derived from the name on create (unless customizing) and kept on update
    const { key, ...rest } = agentPayload(this.draft());
    const req = existing?.id
      ? this.api.updateAgent(existing.id, rest)
      : this.api.createAgent(key ? { key, ...rest } : rest);
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.initialJson.set(JSON.stringify(this.draft()));
        this.message.success(
          existing
            ? $localize`:@@library.agents.saved:Agent "${saved.name}:name:" saved`
            : $localize`:@@library.agents.created:Agent "${saved.name}:name:" created`,
        );
        this.saved.emit(saved);
      },
      error: () => this.saving.set(false),
    });
  }
}

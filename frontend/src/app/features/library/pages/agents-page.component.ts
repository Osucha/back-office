import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type { Agent, AgentInput, ModelProfile, Project } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { agentKindDescription, agentKindLabel } from '../../../core/ui/agents';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent, type FilterChip } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SidePanelComponent, panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { LibraryNavComponent } from '../components/library-nav.component';
import {
  AGENT_KINDS,
  type AgentKindValue,
  agentDraft,
  agentSavable,
  customizeAgentForProject,
  emptyAgent,
  harnessOf,
  kindValue,
} from '../data/agent-form';
import { canCreateInLibrary, canEditScope, editableProjects } from '../data/library-access';
import { filterAgents } from '../data/library-filters';

/**
 * Library: agents (ADR 0056). An agent runs tasks: a harness (none = one model answer; OpenCode or
 * Claude Code = a coding agent in the runner sandbox) and optionally a model. Built-in agents are
 * read-only; a project customises one with a project agent of the same key. The editor is a side
 * panel, deep-linkable with `?agent=<id>` (`?agent=new`).
 */
@Component({
  selector: 'app-agents-page',
  imports: [
    ReadonlyFormDirective,
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    NzRadioModule,
    NzFormModule,
    NzTooltipModule,
    ActionBarComponent,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SidePanelComponent,
    SkeletonComponent,
    SortHeaderDirective,
    LibraryNavComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        title="Library"
        i18n-title="@@library.agents.header.title"
        subtitle="An agent runs tasks: one model answer, or a coding agent (OpenCode, Claude Code) in the runner sandbox with the repository, optionally with its own model."
        i18n-subtitle="@@library.agents.header.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        @if (canCreate()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <span i18n="@@library.agents.create">Create agent</span>
          </button>
        }
        <app-library-nav boHeaderBelow active="agents" [counts]="{ agents: items().length }" />
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search name, kind or tag"
        i18n-placeholder="@@library.agents.filter.placeholder"
        label="Filter agents"
        i18n-label="@@library.agents.filter.label"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [chips]="chips()"
        (chipRemoved)="removeChip($event)"
        (cleared)="f.clear()"
      >
        <nz-select
          [ngModel]="f.values().projectId"
          (ngModelChange)="f.set({ projectId: $event })"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="Global agents"
          i18n-nzPlaceHolder="@@library.agents.filter.global"
          style="width: 200px"
          aria-label="Include project agents"
          i18n-aria-label="@@library.agents.filter.project"
        >
          @for (p of projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="globalPlus(p.key)" />
          }
        </nz-select>
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="4"
          [columns]="5"
          label="Loading agents"
          i18n-label="@@library.agents.loading"
        />
      } @else if (!filtered().length) {
        @if (f.active()) {
          <bo-empty
            title="No agent matches these filters."
            i18n-title="@@library.agents.empty.noMatch"
            actionLabel="Clear filters"
            i18n-actionLabel="@@library.agents.empty.clear"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No agents yet."
            i18n-title="@@library.agents.empty.title"
            detail="An agent decides how a task runs: one model answer or a coding agent with the repository."
            i18n-detail="@@library.agents.empty.detail"
            [actionLabel]="canCreate() ? text.create : null"
            (action)="create()"
          />
        }
      } @else {
        <nz-table
          #t
          [nzData]="rows()"
          [nzShowPagination]="rows().length > 50"
          [nzPageSize]="50"
          nzSize="middle"
        >
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@library.agents.col.agent">
                Agent
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="kind"
                nzWidth="140px"
                i18n="@@library.agents.col.kind"
              >
                Kind
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="model"
                nzWidth="20%"
                i18n="@@library.agents.col.model"
              >
                Model
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="scope"
                nzWidth="106px"
                i18n="@@library.agents.col.scope"
              >
                Scope
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="updated"
                nzWidth="190px"
                i18n="@@library.agents.col.updated"
              >
                Updated
              </th>
              <th nzWidth="84px">
                <span class="sr-only" i18n="@@library.agents.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (a of t.data; track idOf(a); let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i || panel.value() === idOf(a)"
                [attr.data-nav-index]="i"
                tabindex="0"
                (click)="edit(a)"
                (keydown.enter)="edit(a)"
                [attr.aria-label]="a.name"
              >
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      <span class="cell-title">{{ a.name }}</span>
                      @if (a.builtIn) {
                        <span class="chip outline"
                          ><nz-icon nzType="lock" />
                          <span i18n="@@library.agents.builtIn">built-in</span></span
                        >
                      }
                      @for (tag of a.tags ?? []; track tag) {
                        <span class="chip">{{ tag }}</span>
                      }
                    </div>
                    @if (a.description) {
                      <span class="cell-sub" [attr.title]="a.description">{{ a.description }}</span>
                    }
                  </div>
                </td>
                <td>
                  <span
                    class="chip"
                    [class.accent]="!!a.harness"
                    nz-tooltip
                    [nzTooltipTitle]="kindDescription(a.harness)"
                    [attr.data-kind]="a.harness ?? 'MODEL_ANSWER'"
                    >{{ kindLabel(a.harness) }}</span
                  >
                </td>
                <td>
                  @if (a.modelProfileId) {
                    <span>{{ modelName(a.modelProfileId) }}</span>
                  } @else {
                    <span class="quiet" i18n="@@library.agents.model.default"
                      >Default for the step</span
                    >
                  }
                </td>
                <td>
                  @if (a.scope.type === 'PROJECT') {
                    <span class="mono-cell">{{ projectKey(a.scope.projectId) }}</span>
                  } @else {
                    <span class="ink-2" i18n="@@library.agents.scope.global">Global</span>
                  }
                </td>
                <td>
                  <bo-provenance
                    verb="by"
                    i18n-verb="@@library.agents.by"
                    [actor]="a.updatedBy"
                    [at]="a.updatedAt"
                  />
                </td>
                <td (click)="$event.stopPropagation()">
                  <div class="row-actions">
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      (click)="edit(a)"
                      [attr.aria-label]="rowActionLabel(a)"
                    >
                      <nz-icon [nzType]="canEdit(a) ? 'edit' : 'eye'" />
                    </button>
                    @if (canEdit(a)) {
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        class="del"
                        type="button"
                        (click)="remove(a)"
                        nz-tooltip
                        [nzTooltipTitle]="text.delete"
                        [attr.aria-label]="deleteLabel(a.name)"
                      >
                        <nz-icon nzType="delete" />
                      </button>
                    }
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>

    <bo-side-panel
      [open]="panelOpen()"
      [title]="editing() ? editing()!.name : text.newTitle"
      [subtitle]="editing() ? (editing()!.description ?? '') : text.newSubtitle"
      [defaultWidth]="640"
      storageKey="backoffice.panelWidth.agent"
      [bodyLabel]="canEditDraft() ? null : text.details"
      initialFocus="#ag-name"
      (closed)="requestClose()"
    >
      @if (editing()?.builtIn) {
        <span boPanelHeader class="chip outline"
          ><nz-icon nzType="lock" /> <span i18n="@@library.agents.builtIn">built-in</span></span
        >
      }
      @if (editing()) {
        <bo-provenance
          boPanelHeader
          verb="updated by"
          i18n-verb="@@library.agents.updatedBy"
          [actor]="editing()!.updatedBy"
          [at]="editing()!.updatedAt"
        />
      }
      @if (editing()?.builtIn) {
        <p class="field-help builtin-note" data-builtin-note>
          <span i18n="@@library.agents.builtInNote"
            >Built-in agents cannot be changed. To use another model in a project, customize the
            agent for that project.</span
          >
        </p>
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
          <nz-form-label nzFor="ag-model" i18n="@@library.agents.model">Model</nz-form-label>
          <nz-form-control [nzExtra]="text.modelHelp">
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
        <div class="grid">
          <nz-form-item>
            <nz-form-label i18n="@@library.agents.scope">Scope</nz-form-label>
            <nz-form-control>
              <nz-radio-group
                name="scope"
                [ngModel]="draft().scope.type"
                (ngModelChange)="setScope($event)"
                [disabled]="!!editing()"
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
                  [nzDisabled]="!!editing()"
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
        @if (editing()?.key || draft().key) {
          <details class="advanced">
            <summary i18n="@@library.agents.advanced">Advanced</summary>
            <p class="field-help key-note" data-agent-key>
              <span i18n="@@library.agents.keyNote">Technical key (MCP, support):</span>
              <span class="mono">{{ editing()?.key ?? draft().key }}</span>
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
          [showSave]="panelOpen() && canEditDraft()"
          [saveLabel]="editing() ? text.save : text.create"
          [cleanLabel]="canEditDraft() ? text.noChanges : text.readOnly"
          (save)="save()"
        >
          @if (canCustomize()) {
            <button nz-button type="button" (click)="customize()" data-customize>
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
    RELAY_TABLE_STYLES,
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
export class AgentsPageComponent {
  private readonly api = inject(TaskLibraryApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly profilesApi = inject(ModelProfilesApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  readonly identity = inject(IdentityService);

  readonly f = urlFilters({ q: 'string', projectId: 'string' });
  readonly panel = panelParam('agent');
  readonly items = signal<Agent[]>([]);
  readonly projects = signal<Project[]>([]);
  readonly profiles = signal<ModelProfile[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly saving = signal(false);
  readonly draft = signal<AgentInput>(emptyAgent());
  /** A prefilled draft for `?agent=new` (Customize for project). */
  private readonly prefill = signal<AgentInput | null>(null);
  private readonly initialJson = signal(JSON.stringify(emptyAgent()));

  readonly text = {
    reload: $localize`:@@library.agents.reload:Reload agents`,
    create: $localize`:@@library.agents.create:Create agent`,
    save: $localize`:@@library.agents.save:Save agent`,
    delete: $localize`:@@library.agents.delete:Delete agent`,
    newTitle: $localize`:@@library.agents.newTitle:New agent`,
    newSubtitle: $localize`:@@library.agents.newSubtitle:Who runs a task: a model answer or a coding agent`,
    details: $localize`:@@library.agents.details:Agent details`,
    noChanges: $localize`:@@library.agents.noChanges:No changes`,
    readOnly: $localize`:@@library.agents.readOnly:Read-only: built-in, or you cannot change agents in this scope`,
    discard: $localize`:@@library.agents.discard:Discard changes`,
    close: $localize`:@@library.agents.close:Close`,
    modelHelp: $localize`:@@library.agents.model.help:Empty: the step's model or the defaults for its kind (project, starting user, platform).`,
  };

  readonly kinds = AGENT_KINDS.map((h) => ({
    value: kindValue(h),
    label: agentKindLabel(h),
    help: agentKindDescription(h),
  }));
  readonly kindLabel = agentKindLabel;
  readonly kindDescription = agentKindDescription;

  readonly canCreate = computed(() => canCreateInLibrary(this.identity));
  readonly scopeProjects = computed(() => editableProjects(this.identity, this.projects()));
  readonly filtered = computed(() => filterAgents(this.items(), this.f.values().q));
  readonly sort = tableSort<Agent>({
    name: (a) => a.name,
    kind: (a) => agentKindLabel(a.harness),
    model: (a) => (a.modelProfileId ? this.modelName(a.modelProfileId) : ''),
    // global agents first, then project agents by project key
    scope: (a) => (a.scope.type === 'PROJECT' ? `1 ${this.projectKey(a.scope.projectId)}` : '0'),
    updated: (a) => a.updatedAt,
  });
  readonly rows = computed(() => this.sort.apply(this.filtered()));
  readonly editing = computed(() => {
    const id = this.panel.value();
    return id && id !== 'new' ? (this.items().find((a) => this.idOf(a) === id) ?? null) : null;
  });
  readonly panelOpen = computed(() => {
    const id = this.panel.value();
    return id === 'new' ? this.canCreate() : !!this.editing();
  });
  readonly canEditDraft = computed(() => {
    const a = this.editing();
    return a ? !a.builtIn && canEditScope(this.identity, a.scope) : this.canCreate();
  });
  /** A global agent can be customized for a project the user may edit. */
  readonly canCustomize = computed(() => {
    const a = this.editing();
    return !!a && a.scope.type !== 'PROJECT' && this.scopeProjects().length > 0;
  });
  readonly kind = computed<AgentKindValue>(() => kindValue(this.draft().harness));
  readonly dirty = computed(
    () =>
      this.panelOpen() &&
      this.canEditDraft() &&
      JSON.stringify(this.draft()) !== this.initialJson(),
  );
  readonly canSave = computed(() => agentSavable(this.draft()));
  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    return v.projectId
      ? [
          {
            id: 'projectId',
            label: $localize`:@@library.agents.chip.project:Project: ${this.projectKey(v.projectId)}:key:`,
          },
        ]
      : [];
  });

  readonly nav = listNavigation({
    items: this.rows,
    open: (a) => this.edit(a),
    group: $localize`:@@library.agents.group:Agents`,
    when: () => !this.panelOpen(),
  });
  readonly reload = () => this.load();

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@library.agents.crumb.library:Library`, link: '/library/templates' },
      { label: $localize`:@@library.agents.crumb.agents:Agents` },
    ]);
    this.projectsApi.list().subscribe((p) => this.projects.set(p));
    this.profilesApi.listCallable().subscribe((p) => this.profiles.set(p));
    effect(() => {
      this.f.values().projectId;
      untracked(() => this.load());
    });
    effect(() => {
      const a = this.editing();
      this.panel.value();
      untracked(() => {
        const d = a ? agentDraft(a) : (this.prefill() ?? emptyAgent());
        this.draft.set(d);
        this.initialJson.set(JSON.stringify(a ? d : emptyAgent()));
      });
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@library.agents.key.search:Search agents`,
          group: $localize`:@@library.agents.group:Agents`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@library.agents.create:Create agent`,
          group: $localize`:@@library.agents.group:Agents`,
          when: () => this.canCreate() && !this.panelOpen(),
          handler: () => this.create(),
        },
      ],
      inject(DestroyRef),
    );
  }

  idOf(a: Agent): string {
    return a.id ?? a.key ?? '';
  }

  canEdit(a: Agent): boolean {
    return !a.builtIn && canEditScope(this.identity, a.scope);
  }

  modelName(id: string): string {
    return (
      this.profiles().find((p) => p.id === id)?.displayName ??
      $localize`:@@library.agents.model.unknown:Unknown model profile`
    );
  }

  removeChip(id: string): void {
    if (id === 'projectId') this.f.set({ projectId: null });
  }

  projectKey(id: string | null | undefined): string {
    return (
      this.projects().find((p) => p.id === id)?.key ??
      $localize`:@@library.agents.projectFallback:Project`
    );
  }

  globalPlus(key: string): string {
    return $localize`:@@library.agents.globalPlus:Global + ${key}:key:`;
  }

  rowActionLabel(a: Agent): string {
    return this.canEdit(a)
      ? $localize`:@@library.agents.editRow:Edit ${a.name}:name:`
      : $localize`:@@library.agents.viewRow:View ${a.name}:name:`;
  }

  deleteLabel(name: string): string {
    return $localize`:@@library.agents.deleteRow:Delete ${name}:name:`;
  }

  load(): void {
    this.loading.set(true);
    this.api.listAgents(this.f.values().projectId).subscribe({
      next: (list) => {
        this.items.set(list);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  create(): void {
    this.prefill.set(null);
    this.panel.open('new');
  }

  edit(a: Agent): void {
    this.panel.open(this.idOf(a));
  }

  /** Opens a new project agent with the key, kind and model of the open global one. */
  customize(): void {
    const a = this.editing();
    if (!a) return;
    const projectId =
      this.scopeProjects().find((p) => p.id === this.f.values().projectId)?.id ??
      this.scopeProjects()[0]?.id ??
      null;
    this.prefill.set(customizeAgentForProject(a, projectId));
    this.panel.open('new');
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
    this.prefill.set(null);
    this.panel.close();
  }

  patch(changes: Partial<AgentInput>): void {
    this.draft.update((d) => ({ ...d, ...changes }));
  }

  setKind(value: AgentKindValue): void {
    this.patch({ harness: harnessOf(value) });
  }

  setScope(type: 'GLOBAL' | 'PROJECT'): void {
    this.patch({
      scope: {
        type,
        projectId:
          type === 'PROJECT'
            ? (this.draft().scope.projectId ??
              this.f.values().projectId ??
              this.scopeProjects()[0]?.id ??
              null)
            : null,
      },
    });
  }

  save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    const existing = this.editing();
    // the key is derived from the name on create (unless customizing) and kept on update
    const { key, ...rest } = this.draft();
    const req = existing?.id
      ? this.api.updateAgent(existing.id, rest)
      : this.api.createAgent(key ? { key, ...rest } : rest);
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.initialJson.set(JSON.stringify(this.draft()));
        this.prefill.set(null);
        this.panel.close();
        this.message.success(
          existing
            ? $localize`:@@library.agents.saved:Agent "${saved.name}:name:" saved`
            : $localize`:@@library.agents.created:Agent "${saved.name}:name:" created`,
        );
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  async remove(a: Agent): Promise<void> {
    if (a.builtIn || !a.id) return;
    const ok = await this.confirm.danger(
      $localize`:@@library.agents.deleteConfirm.title:Delete agent "${a.name}:name:"?`,
      $localize`:@@library.agents.deleteConfirm.content:Refused while a template uses the agent: choose another agent in those templates first.`,
      $localize`:@@library.agents.delete:Delete agent`,
    );
    if (!ok) return;
    // a refusal (409, still used by a template) is shown by the error interceptor with its detail
    this.api.deleteAgent(a.id).subscribe(() => {
      this.message.success($localize`:@@library.agents.deleted:Agent deleted`);
      if (this.panel.value() === this.idOf(a)) this.panel.close();
      this.load();
    });
  }
}

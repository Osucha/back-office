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
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type { Agent, AgentInput, ModelProfile, Project } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { IdentityService } from '../../../core/auth/identity.service';
import {
  agentAccessText,
  agentDirectoryLabel,
  agentKindDescription,
  agentKindLabel,
  agentSessionLabel,
} from '../../../core/ui/agents';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent, type FilterChip } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { LibraryNavComponent } from '../components/library-nav.component';
import { customizeAgentForProject } from '../data/agent-form';
import { AgentEditorPanelComponent } from '../components/agent-editor-panel.component';
import { canCreateInLibrary, canEditScope, editableProjects } from '../data/library-access';
import { filterAgents } from '../data/library-filters';

/**
 * Library: agents (ADR 0056/0057). An agent is who works and how: a harness (none = one model
 * answer; OpenCode or Claude Code = a coding agent in the runner sandbox), optionally a model, and
 * its environment: instructions, rules, skills, MCP servers (coding agents). Built-in agents are
 * read-only; a project customises one with a project agent of the same key. The editor is a side
 * panel, deep-linkable with `?agent=<id>` (`?agent=new`).
 */
@Component({
  selector: 'app-agents-page',
  imports: [
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    NzTooltipModule,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    AgentEditorPanelComponent,
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
        subtitle="An agent is who works and how: harness, model, instructions, rules and skills. A task template is what to do; a workflow step joins the two."
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
              <th nzWidth="150px" i18n="@@library.agents.col.access">Access</th>
              <th nzWidth="150px" i18n="@@library.agents.col.session">Session</th>
              <th nzWidth="160px" i18n="@@library.agents.col.environment">Environment</th>
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
                  @if (a.harness) {
                    <span class="ink-2" data-access>{{ accessText(a.access) }}</span>
                    @if (a.access?.directory === 'SEPARATE') {
                      <div class="quiet" data-directory>
                        {{ directoryText(a.access?.directory) }}
                      </div>
                    }
                  } @else {
                    <span class="quiet">—</span>
                  }
                </td>
                <td>
                  @if (a.harness) {
                    <span class="ink-2" data-session>{{ sessionText(a.session) }}</span>
                  } @else {
                    <span class="quiet">—</span>
                  }
                </td>
                <td>
                  <span class="ink-2" data-environment>{{ environmentLabel(a) }}</span>
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

    <app-agent-editor-panel
      [open]="panelOpen()"
      [agent]="editing()"
      [prefill]="prefill()"
      [projects]="projects()"
      [profiles]="profiles()"
      [projectId]="f.values().projectId ?? null"
      [showCustomize]="canCustomize()"
      (customize)="customize()"
      (saved)="onSaved()"
      (closed)="closePanel()"
    />
  `,
  styles: [RELAY_TABLE_STYLES],
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
  /** A prefilled draft for `?agent=new` (Customize for project). */
  readonly prefill = signal<AgentInput | null>(null);

  readonly text = {
    reload: $localize`:@@library.agents.reload:Reload agents`,
    create: $localize`:@@library.agents.create:Create agent`,
    delete: $localize`:@@library.agents.delete:Delete agent`,
  };

  readonly kindLabel = agentKindLabel;
  readonly accessText = agentAccessText;
  /** ADR 0062: the session and the directory of a coding agent. */
  readonly sessionText = agentSessionLabel;
  readonly directoryText = agentDirectoryLabel;
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
  /** A global agent can be customized for a project the user may edit. */
  readonly canCustomize = computed(() => {
    const a = this.editing();
    return !!a && a.scope.type !== 'PROJECT' && this.scopeProjects().length > 0;
  });
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

  /** ADR 0057: "2 rules · 3 skills" (the list shows the size of the agent's environment). */
  environmentLabel(a: Agent): string {
    const rules = icu(
      $localize`:@@library.agents.rulesCount:{count, plural, one {# rule} other {# rules}}`,
      {
        count: a.rules?.length ?? 0,
      },
    );
    const skills = icu(
      $localize`:@@library.agents.skillsCount:{count, plural, one {# skill} other {# skills}}`,
      { count: a.skills?.length ?? 0 },
    );
    return `${rules} · ${skills}`;
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

  /** The editor closed (saved or discarded). */
  closePanel(): void {
    this.prefill.set(null);
    this.panel.close();
  }

  onSaved(): void {
    this.closePanel();
    this.load();
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

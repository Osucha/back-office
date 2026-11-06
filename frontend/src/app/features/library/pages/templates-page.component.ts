import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { forkJoin } from 'rxjs';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type {
  Agent,
  ModelProfile,
  Project,
  Rule,
  Skill,
  TaskTemplate,
  TaskTemplateInput,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { icu } from '../../../core/i18n/icu';
import type { HasUnsavedChanges } from '../../../core/auth/guards';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent, type FilterChip } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { LibraryNavComponent } from '../components/library-nav.component';
import { TemplateEditorDrawerComponent } from '../components/template-editor-drawer.component';
import {
  canCreateInLibrary,
  canCreateProjectItems,
  canEditScope,
  editableProjects,
} from '../data/library-access';
import { agentKindDescription, agentKindLabel } from '../../../core/ui/agents';
import { deprecatedHint, deprecatedLabel, isDeprecated } from '../../../core/ui/template-tags';
import {
  TEMPLATE_CATEGORIES,
  categoryLabel,
  categoryOrder,
} from '../../../core/ui/template-categories';
import {
  effectiveCategory,
  filterTemplates,
  nameOfKey,
  templateAgent,
} from '../data/library-filters';
import { customizeForProject } from '../data/resolved-preview';

/**
 * Library: task templates. Filters (search, project templates, category, agent) live in the URL; `j` / `k`
 * / `Enter` open a template in the editor, `?key=` deep-links one.
 */
@Component({
  selector: 'app-templates-page',
  imports: [
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    NzSwitchModule,
    NzTooltipModule,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
    SortHeaderDirective,
    LibraryNavComponent,
    TemplateEditorDrawerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="templates"
        title="Library"
        i18n-title="@@library.templates.header.title"
        subtitle="Templates are reusable tasks: each builds on a parent, adds skills and rules, and can be customised per project."
        i18n-subtitle="@@library.templates.header.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        @if (canCreate()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="newTemplate()">
            <nz-icon nzType="plus" />
            <span i18n="@@library.templates.new">New template</span>
          </button>
        }
        <app-library-nav
          boHeaderBelow
          active="templates"
          [counts]="{ templates: templates().length }"
        />
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search name, category or tag"
        i18n-placeholder="@@library.templates.filter.placeholder"
        label="Filter templates"
        i18n-label="@@library.templates.filter.label"
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
          nzPlaceHolder="Global templates"
          i18n-nzPlaceHolder="@@library.templates.filter.global"
          style="width: 210px"
          aria-label="Include project templates"
          i18n-aria-label="@@library.templates.filter.project"
        >
          @for (p of projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="globalPlus(p.key)" />
          }
        </nz-select>
        <nz-select
          [ngModel]="f.values().category"
          (ngModelChange)="f.set({ category: $event })"
          nzAllowClear
          nzPlaceHolder="Any category"
          i18n-nzPlaceHolder="@@library.templates.filter.anyCategory"
          style="width: 170px"
          aria-label="Filter by category"
          i18n-aria-label="@@library.templates.filter.category"
        >
          @for (c of categories; track c.value) {
            <nz-option [nzValue]="c.value" [nzLabel]="c.label" />
          }
        </nz-select>
        <nz-select
          [ngModel]="f.values().agent"
          (ngModelChange)="f.set({ agent: $event })"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="Any agent"
          i18n-nzPlaceHolder="@@library.templates.filter.anyAgent"
          style="width: 170px"
          aria-label="Filter by agent"
          i18n-aria-label="@@library.templates.filter.agent"
        >
          @for (a of agentFilterOptions(); track a.key) {
            <nz-option [nzValue]="a.key" [nzLabel]="a.name" />
          }
        </nz-select>
        <label class="show-deprecated">
          <nz-switch
            nzSize="small"
            [ngModel]="!!f.values().deprecated"
            (ngModelChange)="f.set({ deprecated: $event ? '1' : null })"
          />
          <span i18n="@@library.templates.filter.showDeprecated">Show deprecated</span>
        </label>
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="8"
          [columns]="6"
          label="Loading templates"
          i18n-label="@@library.templates.loading"
        />
      } @else if (!filtered().length) {
        @if (f.active()) {
          <bo-empty
            title="No template matches these filters."
            i18n-title="@@library.templates.empty.noMatch"
            actionLabel="Clear filters"
            i18n-actionLabel="@@library.templates.empty.clear"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No templates yet."
            i18n-title="@@library.templates.empty.title"
            detail="A template is a reusable task: prompt, agent, skills and rules."
            i18n-detail="@@library.templates.empty.detail"
            [actionLabel]="canCreate() ? text.new : null"
            (action)="newTemplate()"
            help="templates"
            guideLabel="How templates work"
            i18n-guideLabel="@@library.templates.empty.guide"
          />
        }
      } @else {
        <nz-table
          #t
          [nzData]="rows()"
          [nzShowPagination]="rows().length > 50"
          [nzPageSize]="50"
          nzSize="middle"
          [nzShowTotal]="totalTpl"
        >
          <ng-template #totalTpl let-total>{{ totalLabel(total) }}</ng-template>
          <thead>
            <tr>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="name"
                i18n="@@library.templates.col.template"
              >
                Template
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="extends"
                nzWidth="146px"
                i18n="@@library.templates.col.extends"
              >
                Based on
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="agent"
                nzWidth="136px"
                i18n="@@library.templates.col.agent"
              >
                Agent
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="scope"
                nzWidth="106px"
                i18n="@@library.templates.col.scope"
              >
                Scope
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="updated"
                nzWidth="200px"
                i18n="@@library.templates.col.updated"
              >
                Updated
              </th>
              <th nzWidth="118px">
                <span class="sr-only" i18n="@@library.templates.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (tpl of t.data; track tpl.id ?? tpl.key; let i = $index) {
              @if (i === 0 || categoryOf(tpl) !== categoryOf(t.data[i - 1])) {
                <tr class="group-row" [attr.data-category-group]="categoryOf(tpl)">
                  <td colspan="6">
                    <span class="group-title">{{ categoryName(categoryOf(tpl)) }}</span>
                  </td>
                </tr>
              }
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i"
                [attr.data-nav-index]="i"
                tabindex="0"
                (click)="edit(tpl)"
                (keydown.enter)="edit(tpl)"
                [attr.aria-label]="tpl.name"
              >
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      <span class="cell-title">{{ tpl.name }}</span>
                      @if (tpl.builtIn) {
                        <span class="chip outline"
                          ><nz-icon nzType="lock" />
                          <span i18n="@@library.templates.builtIn">built-in</span></span
                        >
                      }
                      @if (tpl.abstract) {
                        <span class="chip outline" i18n="@@library.templates.abstract"
                          >abstract</span
                        >
                      }
                      @if (isDeprecated(tpl)) {
                        <span
                          class="chip deprecated"
                          nz-tooltip
                          [nzTooltipTitle]="text.deprecatedHint"
                          >{{ text.deprecated }}</span
                        >
                      }
                    </div>
                    @if (tpl.description) {
                      <span class="cell-sub" [attr.title]="tpl.description">{{
                        tpl.description
                      }}</span>
                    }
                    @if (tpl.tags?.length || tpl.skills?.length) {
                      <span class="meta-line">
                        @for (tag of tpl.tags ?? []; track tag) {
                          <span>#{{ tag }}</span>
                        }
                        @if (tpl.skills?.length) {
                          <span>{{ skillsLabel(tpl.skills?.length ?? 0) }}</span>
                        }
                      </span>
                    }
                  </div>
                </td>
                <td>
                  @if (tpl.extendsKey) {
                    <span class="ink-2">{{ parentName(tpl) }}</span>
                  } @else {
                    <span class="quiet">-</span>
                  }
                </td>
                <td>
                  @if (agentOf(tpl); as a) {
                    <span
                      [class.chip]="!!tpl.agentKey"
                      [class.accent]="!!tpl.agentKey && !!a.harness"
                      [class.quiet]="!tpl.agentKey"
                      nz-tooltip
                      [nzTooltipTitle]="agentTooltip(tpl, a)"
                      [attr.data-agent]="a.key"
                      >{{ a.name }}</span
                    >
                  }
                </td>
                <td>
                  @if (tpl.scope.type === 'PROJECT') {
                    <span class="mono-cell">{{ projectKey(tpl.scope.projectId) }}</span>
                  } @else {
                    <span class="ink-2" i18n="@@library.templates.global">Global</span>
                  }
                </td>
                <td>
                  <div class="cell-line nowrap">
                    <span class="mono-cell">v{{ tpl.version ?? 1 }}</span>
                    <bo-provenance
                      verb="by"
                      i18n-verb="@@library.templates.by"
                      [actor]="tpl.updatedBy"
                      [at]="tpl.updatedAt"
                    />
                  </div>
                </td>
                <td (click)="$event.stopPropagation()">
                  <div class="row-actions">
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      (click)="edit(tpl)"
                      nz-tooltip
                      [nzTooltipTitle]="canEdit(tpl) ? text.edit : text.view"
                      [attr.aria-label]="rowActionLabel(tpl)"
                    >
                      <nz-icon [nzType]="canEdit(tpl) ? 'edit' : 'eye'" />
                    </button>
                    @if (canCustomize(tpl)) {
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        type="button"
                        (click)="customize(tpl)"
                        nz-tooltip
                        nzTooltipTitle="Customize for project"
                        i18n-nzTooltipTitle="@@library.templates.customize"
                        [attr.aria-label]="customizeLabel(tpl.name)"
                        data-customize
                      >
                        <nz-icon nzType="branches" />
                      </button>
                    }
                    @if (canEdit(tpl)) {
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        class="del"
                        type="button"
                        (click)="remove(tpl)"
                        [disabled]="!!tpl.builtIn"
                        nz-tooltip
                        [nzTooltipTitle]="tpl.builtIn ? text.builtInNoDelete : text.delete"
                        [attr.aria-label]="deleteLabel(tpl.name)"
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

    <app-template-editor-drawer
      [open]="drawerOpen()"
      [template]="editing()"
      [preset]="preset()"
      [templates]="templates()"
      [rules]="rules()"
      [skills]="skills()"
      [profiles]="profiles()"
      [agents]="agents()"
      [projects]="projects()"
      [saving]="saving()"
      [savedVersion]="savedVersion()"
      [readonly]="drawerReadonly()"
      [canGlobal]="identity.can('library.manage')"
      [scopeProjects]="scopeProjects()"
      (saved)="save($event)"
      (closed)="closeDrawer()"
    />
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .meta-line {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      bo-provenance {
        font-size: 11.5px;
      }
      .nowrap {
        flex-wrap: nowrap;
        white-space: nowrap;
      }
      .show-deprecated {
        display: inline-flex;
        gap: 6px;
        align-items: center;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        white-space: nowrap;
      }
      .group-row td {
        padding-top: 14px !important;
        background: transparent !important;
        border-bottom: 1px solid var(--bo-line);
      }
      .group-title {
        font-size: 11.5px;
        font-weight: 600;
        letter-spacing: 0.04em;
        text-transform: uppercase;
        color: var(--bo-ink-3);
      }
      .chip.deprecated {
        color: var(--bo-blocked-ink);
        box-shadow: inset 0 0 0 1px currentColor;
        background: transparent;
      }
    `,
  ],
})
export class TemplatesPageComponent implements HasUnsavedChanges {
  private readonly api = inject(TaskLibraryApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly profilesApi = inject(ModelProfilesApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  readonly identity = inject(IdentityService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly drawer = viewChild(TemplateEditorDrawerComponent);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  private readonly router = inject(Router);

  /** `?key=` deep link opens the editor for that template. */
  readonly key = input<string | undefined>();
  /** `?create=1` (command palette "Create template") opens the editor for a new template. */
  readonly create = input<string | undefined>();

  readonly f = urlFilters({
    q: 'string',
    projectId: 'string',
    /** ADR 0056: an agent key (own or inherited). */
    agent: 'string',
    /** ADR 0055: a template category (own or inherited). */
    category: 'string',
    /** ADR 0054: `1` shows deprecated templates (hidden by default). */
    deprecated: 'string',
  });
  readonly templates = signal<TaskTemplate[]>([]);
  readonly rules = signal<Rule[]>([]);
  readonly skills = signal<Skill[]>([]);
  readonly profiles = signal<ModelProfile[]>([]);
  /** ADR 0056: global agents plus those of the filtered project. */
  readonly agents = signal<Agent[]>([]);
  readonly projects = signal<Project[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly saving = signal(false);
  readonly drawerOpen = signal(false);
  readonly editing = signal<TaskTemplate | null>(null);
  /** ADR 0055: a prefilled new template ("Customize for project"). */
  readonly preset = signal<TaskTemplateInput | null>(null);
  readonly savedVersion = signal(0);
  readonly canCreate = computed(() => canCreateInLibrary(this.identity));
  readonly canExtend = computed(() => canCreateProjectItems(this.identity));
  readonly categories = TEMPLATE_CATEGORIES.map((c) => ({ value: c, label: categoryLabel(c) }));
  readonly categoryName = categoryLabel;
  readonly scopeProjects = computed(() => editableProjects(this.identity, this.projects()));
  /** Existing templates open read-only without the permission for their scope. */
  readonly drawerReadonly = computed(() => {
    const t = this.editing();
    return t?.id ? !canEditScope(this.identity, t.scope) : !this.canCreate();
  });

  readonly isDeprecated = isDeprecated;
  readonly text = {
    deprecated: deprecatedLabel(),
    deprecatedHint: deprecatedHint(),
    reload: $localize`:@@library.templates.reload:Reload templates`,
    new: $localize`:@@library.templates.new:New template`,
    edit: $localize`:@@library.templates.edit:Edit template`,
    view: $localize`:@@library.templates.view:View template`,
    builtInNoDelete: $localize`:@@library.templates.builtInNoDelete:Built-in templates cannot be deleted; extend them per project`,
    delete: $localize`:@@library.templates.delete:Delete template`,
  };
  /** ADR 0056: the agent per template (own or inherited, else model answer). */
  private readonly agentMap = computed(() => {
    const all = this.templates();
    const agents = this.agents();
    return new Map(
      all.map((t) => {
        const { key, agent } = templateAgent(t, all, agents);
        return [t, { key, name: agent?.name ?? key, harness: agent?.harness ?? null }] as const;
      }),
    );
  });
  /** The agents the filter offers: those the listed templates use. */
  readonly agentFilterOptions = computed(() => {
    const seen = new Map<string, { key: string; name: string }>();
    for (const a of this.agentMap().values()) seen.set(a.key, { key: a.key, name: a.name });
    return [...seen.values()].sort((a, b) => a.name.localeCompare(b.name));
  });
  readonly filtered = computed(() =>
    filterTemplates(this.templates(), {
      query: this.f.values().q,
      agent: this.f.values().agent,
      agents: this.agents(),
      category: this.f.values().category,
      showDeprecated: !!this.f.values().deprecated,
    }),
  );
  readonly sort = tableSort<TaskTemplate>({
    name: (t) => t.name,
    extends: (t) => (t.extendsKey ? this.parentName(t) : null),
    agent: (t) => this.agentOf(t)?.name ?? null,
    // global templates first, then project templates by project key
    scope: (t) =>
      t.scope.type === 'PROJECT' ? `PROJECT ${this.projectKey(t.scope.projectId)}` : 'GLOBAL',
    updated: (t) => t.updatedAt,
  });
  /** ADR 0055: effective category per template (own or inherited). */
  private readonly categoryMap = computed(() => {
    const all = this.templates();
    return new Map(all.map((t) => [t, effectiveCategory(t, all)]));
  });
  /** Grouped by category (in display order), the column sort within each group. */
  readonly rows = computed(() =>
    [...this.sort.apply(this.filtered())].sort(
      (a, b) => categoryOrder(this.categoryOf(a)) - categoryOrder(this.categoryOf(b)),
    ),
  );
  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    if (v.projectId)
      out.push({
        id: 'projectId',
        label: $localize`:@@library.templates.chip.project:Project: ${this.projectKey(v.projectId)}:key:`,
      });
    if (v.category)
      out.push({
        id: 'category',
        label: $localize`:@@library.templates.chip.category:Category: ${categoryLabel(v.category)}:category:`,
      });
    if (v.agent)
      out.push({
        id: 'agent',
        label: $localize`:@@library.templates.chip.agent:Agent: ${this.agentName(v.agent)}:agent:`,
      });
    if (v.deprecated)
      out.push({
        id: 'deprecated',
        label: $localize`:@@library.templates.chip.deprecated:Including deprecated`,
      });
    return out;
  });

  readonly nav = listNavigation({
    items: this.rows,
    open: (t) => this.edit(t),
    group: $localize`:@@library.templates.group:Templates`,
    when: () => !this.drawerOpen(),
  });

  readonly reload = () => this.load();

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@library.templates.crumb.library:Library`, link: '/library/templates' },
      { label: $localize`:@@library.templates.crumb.templates:Templates` },
    ]);
    this.projectsApi.list().subscribe((p) => this.projects.set(p));
    this.profilesApi.listCallable().subscribe((p) => this.profiles.set(p));
    effect(() => {
      this.f.values().projectId;
      untracked(() => this.load());
    });
    effect(() => {
      const key = this.key();
      const list = this.templates();
      if (key && list.length) {
        const t = list.find((x) => x.key === key);
        if (t && !untracked(this.drawerOpen)) untracked(() => this.edit(t));
      }
    });
    effect(() => {
      if (!this.create() || !this.canCreate()) return;
      untracked(() => {
        this.newTemplate();
        void this.router.navigate([], {
          queryParams: { create: null },
          queryParamsHandling: 'merge',
          replaceUrl: true,
        });
      });
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@library.templates.key.search:Search templates`,
          group: $localize`:@@library.templates.group:Templates`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@library.templates.new:New template`,
          group: $localize`:@@library.templates.group:Templates`,
          when: () => this.canCreate() && !this.drawerOpen(),
          handler: () => this.newTemplate(),
        },
      ],
      inject(DestroyRef),
    );
  }

  /** ADR 0056: the agent a template runs with (own or inherited). */
  agentOf(t: TaskTemplate): { key: string; name: string; harness: string | null } | null {
    return this.agentMap().get(t) ?? null;
  }

  agentName(key: string): string {
    return this.agentFilterOptions().find((a) => a.key === key)?.name ?? key;
  }

  agentTooltip(t: TaskTemplate, a: { harness: string | null }): string {
    const kind = `${agentKindLabel(a.harness)}: ${agentKindDescription(a.harness)}`;
    return t.agentKey
      ? kind
      : $localize`:@@library.templates.agentInherited:Inherited. ${kind}:kind:`;
  }

  removeChip(id: string): void {
    if (id === 'projectId') this.f.set({ projectId: null });
    else if (id === 'agent') this.f.set({ agent: null });
    else if (id === 'category') this.f.set({ category: null });
    else if (id === 'deprecated') this.f.set({ deprecated: null });
  }

  canEdit(t: TaskTemplate): boolean {
    return canEditScope(this.identity, t.scope);
  }

  /** ADR 0055: a global template may be customised by those who manage a project's library. */
  canCustomize(t: TaskTemplate): boolean {
    return t.scope.type !== 'PROJECT' && !t.abstract && this.canExtend();
  }

  categoryOf(t: TaskTemplate): string {
    return this.categoryMap().get(t) ?? effectiveCategory(t, this.templates());
  }

  /** The parent's name; a project template customising the global one shows that one. */
  parentName(t: TaskTemplate): string {
    if (t.extendsKey === t.key) {
      const global = this.templates().find((x) => x.key === t.key && x.scope.type !== 'PROJECT');
      return global?.name ?? t.extendsKey ?? '';
    }
    return nameOfKey(this.templates(), t.extendsKey);
  }

  hasUnsavedChanges(): boolean {
    return !!this.drawer()?.dirty();
  }

  projectKey(id: string | null | undefined): string {
    return (
      this.projects().find((p) => p.id === id)?.key ??
      $localize`:@@library.templates.projectFallback:Project`
    );
  }

  globalPlus(key: string): string {
    return $localize`:@@library.templates.globalPlus:Global + ${key}:key:`;
  }

  totalLabel(total: number): string {
    return icu($localize`:@@library.templates.total:{count, plural, other {# templates}}`, {
      count: total,
    });
  }

  skillsLabel(count: number): string {
    return icu(
      $localize`:@@library.templates.skills:{count, plural, one {# skill} other {# skills}}`,
      {
        count,
      },
    );
  }

  rowActionLabel(t: TaskTemplate): string {
    return this.canEdit(t)
      ? $localize`:@@library.templates.editRow:Edit ${t.name}:name:`
      : $localize`:@@library.templates.viewRow:View ${t.name}:name:`;
  }

  customizeLabel(name: string): string {
    return $localize`:@@library.templates.customizeRow:Customize ${name}:name: for a project`;
  }

  deleteLabel(name: string): string {
    return $localize`:@@library.templates.deleteRow:Delete ${name}:name:`;
  }

  load(): void {
    const projectId = this.f.values().projectId;
    this.loading.set(true);
    forkJoin({
      templates: this.api.listTemplates({ projectId, includeAbstract: true }),
      skills: this.api.listSkills(projectId),
      rules: this.api.listRules(projectId),
      agents: this.api.listAgents(projectId),
    }).subscribe({
      next: ({ templates, skills, rules, agents }) => {
        this.templates.set(templates);
        this.agents.set(agents);
        this.rules.set(rules);
        this.skills.set(skills);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  newTemplate(): void {
    this.editing.set(null);
    this.preset.set(null);
    this.drawerOpen.set(true);
  }

  edit(t: TaskTemplate): void {
    this.preset.set(null);
    this.editing.set(t);
    this.drawerOpen.set(true);
  }

  /**
   * ADR 0055: opens a new PROJECT template with the global key, extending the global template;
   * everything else is inherited. The project then sees its version under that key.
   */
  customize(t: TaskTemplate): void {
    const editable = this.scopeProjects();
    const filtered = this.f.values().projectId;
    const projectId = editable.some((p) => p.id === filtered)
      ? (filtered ?? null)
      : (editable[0]?.id ?? null);
    this.editing.set(null);
    this.preset.set(customizeForProject(t, projectId));
    this.drawerOpen.set(true);
  }

  closeDrawer(): void {
    this.drawerOpen.set(false);
    this.editing.set(null);
    this.preset.set(null);
    if (this.key())
      void this.router.navigate([], {
        queryParams: { key: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
  }

  save(input: TaskTemplateInput): void {
    const existing = this.editing();
    this.saving.set(true);
    const req = existing?.id
      ? this.api.updateTemplate(existing.id, input)
      : this.api.createTemplate(input);
    this.pendingSaves.track(req).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.message.success(
          existing?.id
            ? $localize`:@@library.templates.saved:Template "${saved.name}:name:" saved (v${saved.version ?? '?'}:version:)`
            : $localize`:@@library.templates.created:Template "${saved.name}:name:" created`,
        );
        this.preset.set(null);
        this.editing.set(saved);
        this.savedVersion.update((v) => v + 1);
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  async remove(t: TaskTemplate): Promise<void> {
    if (t.builtIn || !t.id) return;
    const ok = await this.confirm.danger(
      $localize`:@@library.templates.deleteConfirm.title:Delete template "${t.name}:name:"?`,
      $localize`:@@library.templates.deleteConfirm.content:Workflows that use it fail validation until they pick another template, and templates extending it can no longer be resolved.`,
      $localize`:@@library.templates.delete:Delete template`,
    );
    if (!ok) return;
    this.api.deleteTemplate(t.id).subscribe(() => {
      this.message.success($localize`:@@library.templates.deleted:Template deleted`);
      this.load();
    });
  }
}

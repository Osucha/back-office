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
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ProjectsApi } from '../../../core/api/projects.service';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type { Project, Skill, SkillInput } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { SLUG_PATTERN } from '../../../core/forms/patterns';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { MarkdownViewerComponent } from '../../../core/ui/markdown-viewer.component';
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
import { canCreateInLibrary, canEditScope, editableProjects } from '../data/library-access';
import { filterSkills, sizeLabel } from '../data/library-filters';

const TEMPLATE = `---
name: my-skill
description: When to use this skill and what it enforces.
---

# My skill

- Concrete rule one.
- Concrete rule two.
`;

const EMPTY: SkillInput = {
  key: '',
  name: '',
  description: '',
  content: TEMPLATE,
  scope: { type: 'GLOBAL', projectId: null },
  tags: [],
};

/**
 * Library: skills (SKILL.md files). The editor is a side panel with an edit / preview switch,
 * deep-linkable with `?skill=<id>` (`?skill=new`).
 */
@Component({
  selector: 'app-skills-page',
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
    NzTabsModule,
    NzTooltipModule,
    ActionBarComponent,
    EmptyComponent,
    FilterBarComponent,
    MarkdownViewerComponent,
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
        help="skills"
        title="Library"
        i18n-title="@@library.skills.header.title"
        subtitle="Skills are reusable SKILL.md files: appended to LLM prompts and written into runner workspaces."
        i18n-subtitle="@@library.skills.header.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        @if (canCreate()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <span i18n="@@library.skills.create">Create skill</span>
          </button>
        }
        <app-library-nav boHeaderBelow active="skills" [counts]="{ skills: items().length }" />
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search key, name or tag"
        i18n-placeholder="@@library.skills.filter.placeholder"
        label="Filter skills"
        i18n-label="@@library.skills.filter.label"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [chips]="chips()"
        (chipRemoved)="f.set({ projectId: null })"
        (cleared)="f.clear()"
      >
        <nz-select
          [ngModel]="f.values().projectId"
          (ngModelChange)="f.set({ projectId: $event })"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="Global skills"
          i18n-nzPlaceHolder="@@library.skills.filter.global"
          style="width: 200px"
          aria-label="Include project skills"
          i18n-aria-label="@@library.skills.filter.project"
        >
          @for (p of projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="globalPlus(p.key)" />
          }
        </nz-select>
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="6"
          [columns]="5"
          label="Loading skills"
          i18n-label="@@library.skills.loading"
        />
      } @else if (!filtered().length) {
        @if (f.active()) {
          <bo-empty
            title="No skill matches these filters."
            i18n-title="@@library.skills.empty.noMatch"
            actionLabel="Clear filters"
            i18n-actionLabel="@@library.skills.empty.clear"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No skills yet."
            i18n-title="@@library.skills.empty.title"
            detail="A skill is a short set of concrete rules an agent follows, such as your coding conventions."
            i18n-detail="@@library.skills.empty.detail"
            [actionLabel]="canCreate() ? text.create : null"
            (action)="create()"
            help="skills"
            guideLabel="How skills work"
            i18n-guideLabel="@@library.skills.empty.guide"
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
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@library.skills.col.skill">Skill</th>
              <th nzShowSort [boSort]="sort" boSortKey="scope" nzWidth="106px" i18n="@@library.skills.scope">Scope</th>
              <th nzShowSort [boSort]="sort" boSortKey="tags" nzWidth="20%" i18n="@@library.skills.tags">Tags</th>
              <th nzShowSort [boSort]="sort" boSortKey="size" nzWidth="116px" nzAlign="right" i18n="@@library.skills.col.size">Size</th>
              <th nzShowSort [boSort]="sort" boSortKey="updated" nzWidth="190px" i18n="@@library.skills.col.updated">Updated</th>
              <th nzWidth="84px">
                <span class="sr-only" i18n="@@library.skills.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (s of t.data; track s.id ?? s.key; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i || panel.value() === idOf(s)"
                [attr.data-nav-index]="i"
                tabindex="0"
                (click)="edit(s)"
                (keydown.enter)="edit(s)"
                [attr.aria-label]="s.name + ', ' + s.key"
              >
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      <span class="cell-title">{{ s.name }}</span>
                      <span class="key">{{ s.key }}</span>
                      @if (s.builtIn) {
                        <span class="chip outline"
                          ><nz-icon nzType="lock" />
                          <span i18n="@@library.skills.builtIn">built-in</span></span
                        >
                      }
                    </div>
                    @if (s.description) {
                      <span class="cell-sub" [attr.title]="s.description">{{ s.description }}</span>
                    }
                  </div>
                </td>
                <td>
                  @if (s.scope.type === 'PROJECT') {
                    <span class="mono-cell">{{ projectKey(s.scope.projectId) }}</span>
                  } @else {
                    <span class="ink-2" i18n="@@library.skills.scope.global">Global</span>
                  }
                </td>
                <td>
                  <div class="chips">
                    @for (tag of s.tags ?? []; track tag) {
                      <span class="chip">{{ tag }}</span>
                    } @empty {
                      <span class="quiet">-</span>
                    }
                  </div>
                </td>
                <td nzAlign="right">
                  <span class="mono-cell">{{ size(s.content.length) }}</span>
                </td>
                <td>
                  <bo-provenance
                    verb="by"
                    i18n-verb="@@library.skills.by"
                    [actor]="s.updatedBy"
                    [at]="s.updatedAt"
                  />
                </td>
                <td (click)="$event.stopPropagation()">
                  <div class="row-actions">
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      (click)="edit(s)"
                      [attr.aria-label]="rowActionLabel(s)"
                    >
                      <nz-icon [nzType]="canEdit(s) ? 'edit' : 'eye'" />
                    </button>
                    @if (canEdit(s)) {
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        class="del"
                        type="button"
                        (click)="remove(s)"
                        [disabled]="!!s.builtIn"
                        nz-tooltip
                        [nzTooltipTitle]="s.builtIn ? text.builtInNoDelete : text.delete"
                        [attr.aria-label]="deleteLabel(s.name)"
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
      [subtitle]="editing() ? '.claude/skills/' + editing()!.key + '/SKILL.md' : text.newSubtitle"
      [defaultWidth]="720"
      storageKey="backoffice.panelWidth.skill"
      [bodyLabel]="canEditDraft() ? null : text.details"
      initialFocus="#sk-name"
      (closed)="requestClose()"
    >
      @if (editing()?.builtIn) {
        <span boPanelHeader class="chip outline"
          ><nz-icon nzType="lock" /> <span i18n="@@library.skills.builtIn">built-in</span></span
        >
      }
      @if (editing()) {
        <bo-provenance
          boPanelHeader
          verb="updated by"
          i18n-verb="@@library.skills.updatedBy"
          [actor]="editing()!.updatedBy"
          [at]="editing()!.updatedAt"
        />
      }
      <form
        nz-form
        nzLayout="vertical"
        [boReadonly]="!canEditDraft()"
        (submit)="$event.preventDefault()"
      >
        <div class="grid">
          <nz-form-item>
            <nz-form-label nzRequired nzFor="sk-key" i18n="@@library.skills.key">Key</nz-form-label>
            <nz-form-control
              [nzValidateStatus]="keyError() ? 'error' : ''"
              [nzErrorTip]="keyError() ?? ''"
              nzExtra="Becomes .claude/skills/<key>/SKILL.md"
              i18n-nzExtra="@@library.skills.key.extra"
            >
              <input
                nz-input
                id="sk-key"
                name="key"
                class="mono"
                [ngModel]="draft().key"
                (ngModelChange)="patch({ key: $event })"
                [readOnly]="!!editing()"
                placeholder="kotlin-hexagonal"
                i18n-placeholder="@@library.skills.key.placeholder"
              />
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzRequired nzFor="sk-name" i18n="@@library.skills.name"
              >Name</nz-form-label
            >
            <nz-form-control>
              <input
                nz-input
                id="sk-name"
                name="name"
                [ngModel]="draft().name"
                (ngModelChange)="patch({ name: $event })"
              />
            </nz-form-control>
          </nz-form-item>
        </div>
        <nz-form-item>
          <nz-form-label nzFor="sk-desc" i18n="@@library.skills.description"
            >Description</nz-form-label
          >
          <nz-form-control>
            <input
              nz-input
              id="sk-desc"
              name="description"
              [ngModel]="draft().description ?? ''"
              (ngModelChange)="patch({ description: $event })"
            />
          </nz-form-control>
        </nz-form-item>
        <div class="grid">
          <nz-form-item>
            <nz-form-label i18n="@@library.skills.scope">Scope</nz-form-label>
            <nz-form-control>
              <nz-radio-group
                name="scope"
                [ngModel]="draft().scope.type"
                (ngModelChange)="setScope($event)"
                [disabled]="!!editing()?.builtIn"
              >
                <label
                  nz-radio-button
                  nzValue="GLOBAL"
                  [nzDisabled]="!identity.can('library.manage')"
                  i18n="@@library.skills.scope.globalOption"
                  >Global</label
                >
                <label nz-radio-button nzValue="PROJECT" i18n="@@library.skills.scope.project"
                  >Project</label
                >
              </nz-radio-group>
              @if (draft().scope.type === 'PROJECT') {
                <nz-select
                  name="scopeProject"
                  nzShowSearch
                  nzPlaceHolder="Project"
                  i18n-nzPlaceHolder="@@library.skills.project"
                  [ngModel]="draft().scope.projectId ?? null"
                  (ngModelChange)="patch({ scope: { type: 'PROJECT', projectId: $event } })"
                  style="width: 100%; margin-top: 6px"
                  aria-label="Project of the skill"
                  i18n-aria-label="@@library.skills.projectLabel"
                >
                  @for (p of scopeProjects(); track p.id) {
                    <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' - ' + p.name" />
                  }
                </nz-select>
              }
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="sk-tags" i18n="@@library.skills.tags">Tags</nz-form-label>
            <nz-form-control>
              <nz-select
                id="sk-tags"
                name="tags"
                nzMode="tags"
                [ngModel]="draft().tags ?? []"
                (ngModelChange)="patch({ tags: $event })"
                nzPlaceHolder="kotlin, review"
                i18n-nzPlaceHolder="@@library.skills.tags.placeholder"
              ></nz-select>
            </nz-form-control>
          </nz-form-item>
        </div>
        <nz-tabs nzSize="small" [nzSelectedIndex]="canEditDraft() ? 0 : 1">
          <nz-tab [nzTitle]="skillFile">
            <textarea
              nz-input
              id="sk-content"
              name="content"
              class="mono-textarea"
              aria-label="SKILL.md content"
              i18n-aria-label="@@library.skills.content"
              [ngModel]="draft().content"
              (ngModelChange)="patch({ content: $event })"
              [nzAutosize]="{ minRows: 16, maxRows: 40 }"
              spellcheck="false"
            ></textarea>
            <p class="field-help" i18n="@@library.skills.content.help">
              Frontmatter name and description, then concrete rules (Agent Skills convention).
            </p>
          </nz-tab>
          <nz-tab nzTitle="Preview" i18n-nzTitle="@@library.skills.preview">
            <div class="preview-box"><app-markdown [source]="previewSource()" /></div>
          </nz-tab>
        </nz-tabs>
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
      .preview-box {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 12px 16px;
        background: var(--bo-surface);
      }
      [boPanelFooter] {
        display: block;
        margin: -13px -20px -12px;
      }
    `,
  ],
})
export class SkillsPageComponent {
  private readonly api = inject(TaskLibraryApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  readonly identity = inject(IdentityService);

  readonly f = urlFilters({ q: 'string', projectId: 'string' });
  readonly panel = panelParam('skill');
  readonly items = signal<Skill[]>([]);
  readonly projects = signal<Project[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly saving = signal(false);
  readonly draft = signal<SkillInput>(structuredClone(EMPTY));
  private readonly initialJson = signal(JSON.stringify(EMPTY));

  readonly skillFile = 'SKILL.md'; // i18n-ignore
  readonly text = {
    reload: $localize`:@@library.skills.reload:Reload skills`,
    create: $localize`:@@library.skills.create:Create skill`,
    save: $localize`:@@library.skills.save:Save skill`,
    builtInNoDelete: $localize`:@@library.skills.builtInNoDelete:Built-in skills cannot be deleted`,
    delete: $localize`:@@library.skills.delete:Delete skill`,
    newTitle: $localize`:@@library.skills.newTitle:New skill`,
    newSubtitle: $localize`:@@library.skills.newSubtitle:Rules an agent follows`,
    details: $localize`:@@library.skills.details:Skill details`,
    noChanges: $localize`:@@library.skills.noChanges:No changes`,
    readOnly: $localize`:@@library.skills.readOnly:Read-only: you cannot change skills in this scope`,
    discard: $localize`:@@library.skills.discard:Discard changes`,
    close: $localize`:@@library.skills.close:Close`,
  };

  readonly canCreate = computed(() => canCreateInLibrary(this.identity));
  readonly scopeProjects = computed(() => editableProjects(this.identity, this.projects()));
  readonly filtered = computed(() => filterSkills(this.items(), this.f.values().q));
  /** Column sorting (URL `?sort=`); the same order for the table and keyboard navigation. */
  readonly sort = tableSort<Skill>({
    name: (s) => s.name,
    // global skills first, then project skills by project key
    scope: (s) => (s.scope.type === 'PROJECT' ? `1 ${this.projectKey(s.scope.projectId)}` : '0'),
    tags: (s) => (s.tags ?? []).join(' '),
    size: (s) => s.content.length,
    updated: (s) => s.updatedAt,
  });
  readonly rows = computed(() => this.sort.apply(this.filtered()));
  readonly editing = computed(() => {
    const id = this.panel.value();
    return id && id !== 'new' ? (this.items().find((s) => this.idOf(s) === id) ?? null) : null;
  });
  readonly panelOpen = computed(() => {
    const id = this.panel.value();
    return id === 'new' ? this.canCreate() : !!this.editing();
  });
  /** The open skill may be changed (its scope), or a new one may be created. */
  readonly canEditDraft = computed(() => {
    const s = this.editing();
    return s ? canEditScope(this.identity, s.scope) : this.canCreate();
  });
  readonly dirty = computed(
    () =>
      this.panelOpen() &&
      this.canEditDraft() &&
      JSON.stringify(this.draft()) !== this.initialJson(),
  );
  readonly chips = computed<FilterChip[]>(() => {
    const p = this.f.values().projectId;
    return p
      ? [
          {
            id: 'projectId',
            label: $localize`:@@library.skills.chip.project:Project: ${this.projectKey(p)}:key:`,
          },
        ]
      : [];
  });

  readonly keyError = computed(() => {
    const k = this.draft().key;
    if (!k) return $localize`:@@library.skills.required:Required`;
    if (!SLUG_PATTERN.test(k) || k.length < 2)
      return $localize`:@@library.skills.keySlug:Slug: lowercase letters, digits, dashes`;
    if (
      !this.editing() &&
      this.items().some((s) => s.key === k && s.scope.type === this.draft().scope.type)
    )
      return $localize`:@@library.skills.keyTaken:Key already exists in this scope`;
    return null;
  });
  readonly canSave = computed(() => {
    const d = this.draft();
    return (
      !this.keyError() &&
      !!d.name.trim() &&
      !!d.content.trim() &&
      (d.scope.type !== 'PROJECT' || !!d.scope.projectId)
    );
  });
  /** Markdown preview without the YAML frontmatter block. */
  readonly previewSource = computed(() => this.draft().content.replace(/^---[\s\S]*?---\s*/, ''));

  readonly nav = listNavigation({
    items: this.rows,
    open: (s) => this.edit(s),
    group: $localize`:@@library.skills.group:Skills`,
    when: () => !this.panelOpen(),
  });
  readonly reload = () => this.load();
  readonly size = sizeLabel;

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@library.skills.crumb.library:Library`, link: '/library/templates' },
      { label: $localize`:@@library.skills.crumb.skills:Skills` },
    ]);
    this.projectsApi.list().subscribe((p) => this.projects.set(p));
    effect(() => {
      this.f.values().projectId;
      untracked(() => this.load());
    });
    effect(() => {
      const s = this.editing();
      this.panel.value();
      untracked(() => {
        const d: SkillInput = s
          ? {
              key: s.key,
              name: s.name,
              description: s.description ?? '',
              content: s.content,
              scope: { type: s.scope?.type ?? 'GLOBAL', projectId: s.scope?.projectId ?? null },
              tags: [...(s.tags ?? [])],
            }
          : structuredClone(EMPTY);
        this.draft.set(d);
        this.initialJson.set(JSON.stringify(d));
      });
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@library.skills.key.search:Search skills`,
          group: $localize`:@@library.skills.group:Skills`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@library.skills.create:Create skill`,
          group: $localize`:@@library.skills.group:Skills`,
          when: () => this.canCreate() && !this.panelOpen(),
          handler: () => this.create(),
        },
      ],
      inject(DestroyRef),
    );
  }

  idOf(s: Skill): string {
    return s.id ?? s.key;
  }

  canEdit(s: Skill): boolean {
    return canEditScope(this.identity, s.scope);
  }

  projectKey(id: string | null | undefined): string {
    return (
      this.projects().find((p) => p.id === id)?.key ??
      $localize`:@@library.skills.projectFallback:Project`
    );
  }

  globalPlus(key: string): string {
    return $localize`:@@library.skills.globalPlus:Global + ${key}:key:`;
  }

  rowActionLabel(s: Skill): string {
    return this.canEdit(s)
      ? $localize`:@@library.skills.editRow:Edit ${s.name}:name:`
      : $localize`:@@library.skills.viewRow:View ${s.name}:name:`;
  }

  deleteLabel(name: string): string {
    return $localize`:@@library.skills.deleteRow:Delete ${name}:name:`;
  }

  load(): void {
    this.loading.set(true);
    this.api.listSkills(this.f.values().projectId).subscribe({
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
    this.panel.open('new');
  }

  edit(s: Skill): void {
    this.panel.open(this.idOf(s));
  }

  async requestClose(): Promise<void> {
    if (this.dirty()) {
      const ok = await this.confirm.confirm({
        title: $localize`:@@library.skills.discard.title:Discard unsaved changes?`,
        content: $localize`:@@library.skills.discard.content:The changes to this skill have not been saved.`,
        okText: $localize`:@@library.skills.discard:Discard changes`,
        danger: true,
      });
      if (!ok) return;
    }
    this.panel.close();
  }

  patch(changes: Partial<SkillInput>): void {
    this.draft.update((d) => ({ ...d, ...changes }));
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
    const req = existing?.id
      ? this.api.updateSkill(existing.id, this.draft())
      : this.api.createSkill(this.draft());
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.initialJson.set(JSON.stringify(this.draft()));
        this.panel.close();
        this.message.success(
          existing
            ? $localize`:@@library.skills.saved:Skill "${saved.key}:key:" saved`
            : $localize`:@@library.skills.created:Skill "${saved.key}:key:" created`,
        );
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  async remove(s: Skill): Promise<void> {
    if (s.builtIn || !s.id) return;
    const ok = await this.confirm.danger(
      $localize`:@@library.skills.deleteConfirm.title:Delete skill "${s.key}:key:"?`,
      $localize`:@@library.skills.deleteConfirm.content:Templates that list it stop receiving it on their next run; their other skills stay.`,
      $localize`:@@library.skills.delete:Delete skill`,
    );
    if (!ok) return;
    this.api.deleteSkill(s.id).subscribe(() => {
      this.message.success($localize`:@@library.skills.deleted:Skill deleted`);
      if (this.panel.value() === this.idOf(s)) this.panel.close();
      this.load();
    });
  }
}

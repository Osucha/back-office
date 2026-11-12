import { HelpTipComponent } from '../../../core/ui/help-tip.component';
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
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ProjectsApi } from '../../../core/api/projects.service';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type { Project, Rule, RuleInput } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
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
import { filterRules } from '../data/library-filters';
import { automaticHint, emptyRule, ruleDraft, ruleSavable } from '../data/rule-form';

/**
 * Library: rules (ADR 0055). A rule is always in the agent's context; an automatic one applies to
 * every step of its scope. The editor is a side panel, deep-linkable with `?rule=<id>` (`?rule=new`).
 */
@Component({
  selector: 'app-rules-page',
  imports: [
    HelpTipComponent,
    ReadonlyFormDirective,
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    NzSwitchModule,
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
        help="rules"
        title="Library"
        i18n-title="@@library.rules.header.title"
        subtitle="A rule is always in the agent's context, for every step it applies to. A skill, in contrast, is knowledge the agent loads when it needs it."
        i18n-subtitle="@@library.rules.header.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        @if (canCreate()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <span i18n="@@library.rules.create">Create rule</span>
          </button>
        }
        <app-library-nav boHeaderBelow active="rules" [counts]="{ rules: items().length }" />
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search name or tag"
        i18n-placeholder="@@library.rules.filter.placeholder"
        label="Filter rules"
        i18n-label="@@library.rules.filter.label"
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
          nzPlaceHolder="Global rules"
          i18n-nzPlaceHolder="@@library.rules.filter.global"
          style="width: 200px"
          aria-label="Include project rules"
          i18n-aria-label="@@library.rules.filter.project"
        >
          @for (p of projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="globalPlus(p.key)" />
          }
        </nz-select>
        <label class="only-automatic">
          <nz-switch
            nzSize="small"
            [ngModel]="!!f.values().automatic"
            (ngModelChange)="f.set({ automatic: $event ? '1' : null })"
          />
          <span i18n="@@library.rules.filter.automatic">Only automatic</span>
        </label>
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="6"
          [columns]="5"
          label="Loading rules"
          i18n-label="@@library.rules.loading"
        />
      } @else if (!filtered().length) {
        @if (f.active()) {
          <bo-empty
            title="No rule matches these filters."
            i18n-title="@@library.rules.empty.noMatch"
            actionLabel="Clear filters"
            i18n-actionLabel="@@library.rules.empty.clear"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No rules yet."
            i18n-title="@@library.rules.empty.title"
            detail="A rule is a short instruction every step it applies to follows, such as never printing secrets."
            i18n-detail="@@library.rules.empty.detail"
            [actionLabel]="canCreate() ? text.create : null"
            (action)="create()"
            help="rules"
            guideLabel="How rules work"
            i18n-guideLabel="@@library.rules.empty.guide"
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
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@library.rules.col.rule">
                Rule
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="scope"
                nzWidth="106px"
                i18n="@@library.rules.col.scope"
              >
                Scope
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="automatic"
                nzWidth="120px"
                i18n="@@library.rules.col.applies"
              >
                Applies
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="tags"
                nzWidth="20%"
                i18n="@@library.rules.col.tags"
              >
                Tags
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="updated"
                nzWidth="190px"
                i18n="@@library.rules.col.updated"
              >
                Updated
              </th>
              <th nzWidth="84px">
                <span class="sr-only" i18n="@@library.rules.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (r of t.data; track idOf(r); let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i || panel.value() === idOf(r)"
                [attr.data-nav-index]="i"
                tabindex="0"
                (click)="edit(r)"
                (keydown.enter)="edit(r)"
                [attr.aria-label]="r.name"
              >
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      <span class="cell-title">{{ r.name }}</span>
                      @if (r.builtIn) {
                        <span class="chip outline"
                          ><nz-icon nzType="lock" />
                          <span i18n="@@library.rules.builtIn">built-in</span></span
                        >
                      }
                    </div>
                    @if (r.description) {
                      <span class="cell-sub" [attr.title]="r.description">{{ r.description }}</span>
                    }
                  </div>
                </td>
                <td>
                  @if (r.scope.type === 'PROJECT') {
                    <span class="mono-cell">{{ projectKey(r.scope.projectId) }}</span>
                  } @else {
                    <span class="ink-2" i18n="@@library.rules.scope.global">Global</span>
                  }
                </td>
                <td>
                  @if (r.automatic) {
                    <span
                      class="chip accent"
                      nz-tooltip
                      [nzTooltipTitle]="hintFor(r)"
                      data-automatic
                      i18n="@@library.rules.automaticBadge"
                      >automatic</span
                    >
                  } @else {
                    <span class="quiet" i18n="@@library.rules.whenListed">when listed</span>
                  }
                </td>
                <td>
                  <div class="chips">
                    @for (tag of r.tags ?? []; track tag) {
                      <span class="chip">{{ tag }}</span>
                    } @empty {
                      <span class="quiet">-</span>
                    }
                  </div>
                </td>
                <td>
                  <bo-provenance
                    verb="by"
                    i18n-verb="@@library.rules.by"
                    [actor]="r.updatedBy"
                    [at]="r.updatedAt"
                  />
                </td>
                <td (click)="$event.stopPropagation()">
                  <div class="row-actions">
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      (click)="edit(r)"
                      [attr.aria-label]="rowActionLabel(r)"
                    >
                      <nz-icon [nzType]="canEdit(r) ? 'edit' : 'eye'" />
                    </button>
                    @if (canEdit(r)) {
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        class="del"
                        type="button"
                        (click)="remove(r)"
                        [disabled]="!!r.builtIn"
                        nz-tooltip
                        [nzTooltipTitle]="r.builtIn ? text.builtInNoDelete : text.delete"
                        [attr.aria-label]="deleteLabel(r.name)"
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
      [defaultWidth]="720"
      storageKey="backoffice.panelWidth.rule"
      [bodyLabel]="canEditDraft() ? null : text.details"
      initialFocus="#ru-name"
      (closed)="requestClose()"
    >
      @if (editing()?.builtIn) {
        <span boPanelHeader class="chip outline"
          ><nz-icon nzType="lock" /> <span i18n="@@library.rules.builtIn">built-in</span></span
        >
      }
      @if (editing()) {
        <bo-provenance
          boPanelHeader
          verb="updated by"
          i18n-verb="@@library.rules.updatedBy"
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
        <nz-form-item>
          <nz-form-label nzRequired nzFor="ru-name" i18n="@@library.rules.name">Name</nz-form-label>
          <nz-form-control>
            <input
              nz-input
              id="ru-name"
              name="name"
              [ngModel]="draft().name"
              (ngModelChange)="patch({ name: $event })"
              placeholder="Never print secrets"
              i18n-placeholder="@@library.rules.name.placeholder"
            />
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzFor="ru-desc" i18n="@@library.rules.description"
            >Description</nz-form-label
          >
          <nz-form-control>
            <input
              nz-input
              id="ru-desc"
              name="description"
              [ngModel]="draft().description ?? ''"
              (ngModelChange)="patch({ description: $event })"
            />
          </nz-form-control>
        </nz-form-item>
        <div class="grid">
          <nz-form-item>
            <nz-form-label i18n="@@library.rules.scope">Scope</nz-form-label>
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
                  i18n="@@library.rules.scope.globalOption"
                  >Global</label
                >
                <label nz-radio-button nzValue="PROJECT" i18n="@@library.rules.scope.project"
                  >Project</label
                >
              </nz-radio-group>
              @if (draft().scope.type === 'PROJECT') {
                <nz-select
                  name="scopeProject"
                  nzShowSearch
                  nzPlaceHolder="Project"
                  i18n-nzPlaceHolder="@@library.rules.project"
                  [ngModel]="draft().scope.projectId ?? null"
                  (ngModelChange)="patch({ scope: { type: 'PROJECT', projectId: $event } })"
                  style="width: 100%; margin-top: 6px"
                  aria-label="Project of the rule"
                  i18n-aria-label="@@library.rules.projectLabel"
                >
                  @for (p of scopeProjects(); track p.id) {
                    <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' - ' + p.name" />
                  }
                </nz-select>
              }
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="ru-tags" i18n="@@library.rules.tags">Tags</nz-form-label>
            <nz-form-control>
              <nz-select
                id="ru-tags"
                name="tags"
                nzMode="tags"
                [ngModel]="draft().tags ?? []"
                (ngModelChange)="patch({ tags: $event })"
                nzPlaceHolder="security, style"
                i18n-nzPlaceHolder="@@library.rules.tags.placeholder"
              ></nz-select>
            </nz-form-control>
          </nz-form-item>
        </div>
        <nz-form-item>
          <nz-form-control [nzExtra]="hint()">
            <label class="automatic-switch">
              <nz-switch
                id="ru-automatic"
                name="automatic"
                [ngModel]="draft().automatic"
                (ngModelChange)="patch({ automatic: $event })"
              />
              <span i18n="@@library.rules.automatic">Apply automatically to every step</span>
            </label>
          </nz-form-control>
        </nz-form-item>
        <ng-template #contentTip><bo-help-tip [text]="contentHelp" /></ng-template>
        <ng-template #contentHelp
          ><span i18n="@@library.rules.content.help">
            Markdown. Keep it short and concrete: it is in the context of every step it applies to.
          </span></ng-template
        >
        <nz-tabs
          nzSize="small"
          [nzSelectedIndex]="canEditDraft() ? 0 : 1"
          [nzTabBarExtraContent]="contentTip"
        >
          <nz-tab nzTitle="Rule" i18n-nzTitle="@@library.rules.contentTab">
            <textarea
              nz-input
              id="ru-content"
              name="content"
              class="mono-textarea"
              aria-label="Rule content"
              i18n-aria-label="@@library.rules.content"
              [ngModel]="draft().content"
              (ngModelChange)="patch({ content: $event })"
              [nzAutosize]="{ minRows: 10, maxRows: 32 }"
              spellcheck="false"
            ></textarea>
          </nz-tab>
          <nz-tab nzTitle="Preview" i18n-nzTitle="@@library.rules.preview">
            <div class="preview-box"><app-markdown [source]="draft().content" /></div>
          </nz-tab>
        </nz-tabs>
        @if (editing()?.key) {
          <p class="field-help key-note" data-rule-key>
            <span i18n="@@library.rules.keyNote">Technical key (MCP, support):</span>
            <span class="mono">{{ editing()!.key }}</span>
          </p>
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
      .automatic-switch,
      .only-automatic {
        display: inline-flex;
        gap: 8px;
        align-items: center;
        white-space: nowrap;
      }
      .only-automatic {
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .preview-box {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 12px 16px;
        background: var(--bo-surface);
      }
      .key-note {
        margin-top: 12px;
        color: var(--bo-ink-3);
      }
      [boPanelFooter] {
        display: block;
        margin: -13px -20px -12px;
      }
    `,
  ],
})
export class RulesPageComponent {
  private readonly api = inject(TaskLibraryApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  readonly identity = inject(IdentityService);

  readonly f = urlFilters({ q: 'string', projectId: 'string', automatic: 'string' });
  readonly panel = panelParam('rule');
  readonly items = signal<Rule[]>([]);
  readonly projects = signal<Project[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly saving = signal(false);
  readonly draft = signal<RuleInput>(emptyRule());
  private readonly initialJson = signal(JSON.stringify(emptyRule()));

  readonly text = {
    reload: $localize`:@@library.rules.reload:Reload rules`,
    create: $localize`:@@library.rules.create:Create rule`,
    save: $localize`:@@library.rules.save:Save rule`,
    builtInNoDelete: $localize`:@@library.rules.builtInNoDelete:Built-in rules cannot be deleted`,
    delete: $localize`:@@library.rules.delete:Delete rule`,
    newTitle: $localize`:@@library.rules.newTitle:New rule`,
    newSubtitle: $localize`:@@library.rules.newSubtitle:An instruction always in the agent's context`,
    details: $localize`:@@library.rules.details:Rule details`,
    noChanges: $localize`:@@library.rules.noChanges:No changes`,
    readOnly: $localize`:@@library.rules.readOnly:Read-only: you cannot change rules in this scope`,
    discard: $localize`:@@library.rules.discard:Discard changes`,
    close: $localize`:@@library.rules.close:Close`,
  };

  readonly canCreate = computed(() => canCreateInLibrary(this.identity));
  readonly scopeProjects = computed(() => editableProjects(this.identity, this.projects()));
  readonly filtered = computed(() =>
    filterRules(this.items(), {
      query: this.f.values().q,
      automatic: !!this.f.values().automatic,
    }),
  );
  readonly sort = tableSort<Rule>({
    name: (r) => r.name,
    // global rules first, then project rules by project key
    scope: (r) => (r.scope.type === 'PROJECT' ? `1 ${this.projectKey(r.scope.projectId)}` : '0'),
    automatic: (r) => (r.automatic ? 0 : 1),
    tags: (r) => (r.tags ?? []).join(' '),
    updated: (r) => r.updatedAt,
  });
  readonly rows = computed(() => this.sort.apply(this.filtered()));
  readonly editing = computed(() => {
    const id = this.panel.value();
    return id && id !== 'new' ? (this.items().find((r) => this.idOf(r) === id) ?? null) : null;
  });
  readonly panelOpen = computed(() => {
    const id = this.panel.value();
    return id === 'new' ? this.canCreate() : !!this.editing();
  });
  readonly canEditDraft = computed(() => {
    const r = this.editing();
    return r ? canEditScope(this.identity, r.scope) : this.canCreate();
  });
  readonly dirty = computed(
    () =>
      this.panelOpen() &&
      this.canEditDraft() &&
      JSON.stringify(this.draft()) !== this.initialJson(),
  );
  readonly canSave = computed(() => ruleSavable(this.draft()));
  /** Where the rule applies with the current scope and switch. */
  readonly hint = computed(() =>
    automaticHint(this.draft(), this.projectKey(this.draft().scope.projectId)),
  );
  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    if (v.projectId)
      out.push({
        id: 'projectId',
        label: $localize`:@@library.rules.chip.project:Project: ${this.projectKey(v.projectId)}:key:`,
      });
    if (v.automatic)
      out.push({
        id: 'automatic',
        label: $localize`:@@library.rules.chip.automatic:Only automatic`,
      });
    return out;
  });

  readonly nav = listNavigation({
    items: this.rows,
    open: (r) => this.edit(r),
    group: $localize`:@@library.rules.group:Rules`,
    when: () => !this.panelOpen(),
  });
  readonly reload = () => this.load();

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@library.rules.crumb.library:Library`, link: '/library/templates' },
      { label: $localize`:@@library.rules.crumb.rules:Rules` },
    ]);
    this.projectsApi.list().subscribe((p) => this.projects.set(p));
    effect(() => {
      this.f.values().projectId;
      untracked(() => this.load());
    });
    effect(() => {
      const r = this.editing();
      this.panel.value();
      untracked(() => {
        const d = r ? ruleDraft(r) : emptyRule();
        this.draft.set(d);
        this.initialJson.set(JSON.stringify(d));
      });
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@library.rules.key.search:Search rules`,
          group: $localize`:@@library.rules.group:Rules`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@library.rules.create:Create rule`,
          group: $localize`:@@library.rules.group:Rules`,
          when: () => this.canCreate() && !this.panelOpen(),
          handler: () => this.create(),
        },
      ],
      inject(DestroyRef),
    );
  }

  idOf(r: Rule): string {
    return r.id ?? r.key ?? '';
  }

  canEdit(r: Rule): boolean {
    return canEditScope(this.identity, r.scope);
  }

  hintFor(r: Rule): string {
    return automaticHint(r, this.projectKey(r.scope.projectId));
  }

  removeChip(id: string): void {
    if (id === 'projectId') this.f.set({ projectId: null });
    else if (id === 'automatic') this.f.set({ automatic: null });
  }

  projectKey(id: string | null | undefined): string {
    return (
      this.projects().find((p) => p.id === id)?.key ??
      $localize`:@@library.rules.projectFallback:Project`
    );
  }

  globalPlus(key: string): string {
    return $localize`:@@library.rules.globalPlus:Global + ${key}:key:`;
  }

  rowActionLabel(r: Rule): string {
    return this.canEdit(r)
      ? $localize`:@@library.rules.editRow:Edit ${r.name}:name:`
      : $localize`:@@library.rules.viewRow:View ${r.name}:name:`;
  }

  deleteLabel(name: string): string {
    return $localize`:@@library.rules.deleteRow:Delete ${name}:name:`;
  }

  load(): void {
    this.loading.set(true);
    this.api.listRules(this.f.values().projectId).subscribe({
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

  edit(r: Rule): void {
    this.panel.open(this.idOf(r));
  }

  async requestClose(): Promise<void> {
    if (this.dirty()) {
      const ok = await this.confirm.confirm({
        title: $localize`:@@library.rules.discard.title:Discard unsaved changes?`,
        content: $localize`:@@library.rules.discard.content:The changes to this rule have not been saved.`,
        okText: $localize`:@@library.rules.discard:Discard changes`,
        danger: true,
      });
      if (!ok) return;
    }
    this.panel.close();
  }

  patch(changes: Partial<RuleInput>): void {
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
    // the key is derived from the name on create and kept on update (never sent)
    const req = existing?.id
      ? this.api.updateRule(existing.id, this.draft())
      : this.api.createRule(this.draft());
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.initialJson.set(JSON.stringify(this.draft()));
        this.panel.close();
        this.message.success(
          existing
            ? $localize`:@@library.rules.saved:Rule "${saved.name}:name:" saved`
            : $localize`:@@library.rules.created:Rule "${saved.name}:name:" created`,
        );
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  async remove(r: Rule): Promise<void> {
    if (r.builtIn || !r.id) return;
    const ok = await this.confirm.danger(
      $localize`:@@library.rules.deleteConfirm.title:Delete rule "${r.name}:name:"?`,
      $localize`:@@library.rules.deleteConfirm.content:Refused while an agent lists the rule: remove it from those agents first.`,
      $localize`:@@library.rules.delete:Delete rule`,
    );
    if (!ok) return;
    this.api.deleteRule(r.id).subscribe(() => {
      this.message.success($localize`:@@library.rules.deleted:Rule deleted`);
      if (this.panel.value() === this.idOf(r)) this.panel.close();
      this.load();
    });
  }
}

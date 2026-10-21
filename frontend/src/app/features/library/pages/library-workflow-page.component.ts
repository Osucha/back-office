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
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { catchError, of } from 'rxjs';
import { InsightsApi } from '../../../core/api/insights.service';
import type {
  LibraryWorkflow,
  LibraryWorkflowAdoption,
  LibraryWorkflowVersionDetail,
  WorkflowDefinition,
  WorkflowDefinitionInput,
  WorkflowInsights,
} from '../../../core/api/types';
import { WorkflowLibraryApi } from '../../../core/api/workflow-library.service';
import { icu } from '../../../core/i18n/icu';
import { IdentityService } from '../../../core/auth/identity.service';
import { ProjectsStore } from '../../../core/state/projects.store';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { PERIODS, type PeriodId, periodRange } from '../../insights/public-api';
import { WorkflowCanvasComponent } from '../../workflows/public-api';
import { AdoptWorkflowDialogComponent } from '../components/adopt-workflow-dialog.component';
import { WorkflowMetricsPanelComponent } from '../components/workflow-metrics-panel.component';
import { updateAvailable } from '../data/workflow-metrics';

const TABS = ['performance', 'graph', 'versions', 'projects'] as const;

/**
 * One library workflow (ADR 0037): its performance across the projects the caller may view (per version, project and
 * step), the graph of any version (read-only canvas), the version history and the projects that use it. Library
 * managers edit the name, description, tags and deprecation, and delete unused entries.
 */
@Component({
  selector: 'app-library-workflow-page',
  imports: [
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzModalModule,
    NzSelectModule,
    NzSwitchModule,
    NzTableModule,
    NzTabsModule,
    EmptyComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
    SortHeaderDirective,
    WorkflowCanvasComponent,
    AdoptWorkflowDialogComponent,
    WorkflowMetricsPanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      @if (!entry()) {
        @if (missing()) {
          <bo-empty
            title="This library workflow does not exist (any more)."
            i18n-title="@@library.workflow.missing"
            actionLabel="Back to the library"
            i18n-actionLabel="@@library.workflow.back"
            (action)="router.navigate(['/library/workflows'])"
          />
        } @else {
          <bo-skeleton
            variant="detail"
            label="Loading library workflow"
            i18n-label="@@library.workflow.loading"
          />
        }
      } @else if (entry(); as w) {
        <bo-page-header
          help="libraryWorkflows"
          [eyebrow]="w.key + ' · v' + w.latestVersion"
          [title]="w.name"
          [subtitle]="w.description || null"
          [menu]="menu()"
        >
          @if (adoptProjects().length && !w.deprecated) {
            <button
              boHeaderActions
              nz-button
              nzType="primary"
              type="button"
              (click)="adoptOpen.set(true)"
            >
              <nz-icon nzType="plus" />
              <span i18n="@@library.workflow.use">Use in a project</span>
            </button>
          }
          <div boHeaderBelow class="chips">
            @if (w.deprecated) {
              <span class="chip outline" i18n="@@library.workflow.deprecated"
                >deprecated: not offered to projects</span
              >
            }
            @for (tag of w.tags ?? []; track tag) {
              <span class="chip">{{ tag }}</span>
            }
            <span class="quiet small">{{ usageText() }}</span>
          </div>
        </bo-page-header>

        <nz-tabs
          [nzSelectedIndex]="tabIndex()"
          (nzSelectedIndexChange)="setTab($event)"
          [nzAnimated]="false"
        >
          <nz-tab nzTitle="Performance" i18n-nzTitle="@@library.workflow.tab.performance">
            <div
              class="periods"
              role="group"
              aria-label="Period"
              i18n-aria-label="@@library.workflow.period"
            >
              @for (p of periods; track p.id) {
                <button
                  type="button"
                  class="period"
                  [class.on]="period() === p.id"
                  [attr.aria-pressed]="period() === p.id"
                  (click)="f.set({ period: p.id === '30d' ? null : p.id })"
                >
                  {{ p.label }}
                </button>
              }
            </div>
            @if (metricsLoading() && !insights()) {
              <bo-skeleton variant="cards" [rows]="1" />
            } @else {
              <app-workflow-metrics-panel [insights]="insights()" scope="library" />
            }
          </nz-tab>

          <nz-tab nzTitle="Graph" i18n-nzTitle="@@library.workflow.tab.graph">
            <ng-template nz-tab>
              <div class="graph-bar">
                <label for="graph-version" i18n="@@library.workflow.graphVersion">Version</label>
                <nz-select
                  id="graph-version"
                  style="width: 220px"
                  [ngModel]="graphVersion()"
                  (ngModelChange)="graphVersion.set($event)"
                >
                  @for (v of w.versions; track v.version) {
                    <nz-option
                      [nzValue]="v.version"
                      [nzLabel]="'v' + v.version + (v.changeNote ? ' · ' + v.changeNote : '')"
                    />
                  }
                </nz-select>
              </div>
              @if (definition(); as def) {
                @defer (on immediate) {
                  <div class="canvas-wrap surface">
                    <app-workflow-canvas
                      [definition]="def"
                      [readonly]="true"
                      [showMinimap]="false"
                    />
                  </div>
                } @placeholder {
                  <bo-skeleton variant="detail" />
                }
              } @else {
                <bo-skeleton variant="detail" />
              }
            </ng-template>
          </nz-tab>

          <nz-tab [nzTitle]="versionsTitle()">
            <nz-table [nzData]="versionRows()" [nzShowPagination]="false" nzSize="middle">
              <thead>
                <tr>
                  <th
                    nzShowSort
                    [boSort]="versionSort"
                    boSortKey="version"
                    nzWidth="96px"
                    i18n="@@library.workflow.col.version"
                  >
                    Version
                  </th>
                  <th
                    nzShowSort
                    [boSort]="versionSort"
                    boSortKey="changes"
                    i18n="@@library.workflow.col.changes"
                  >
                    What changed
                  </th>
                  <th
                    nzShowSort
                    [boSort]="versionSort"
                    boSortKey="nodes"
                    nzWidth="96px"
                    nzAlign="right"
                    i18n="@@library.workflow.col.nodes"
                  >
                    Steps
                  </th>
                  <th
                    nzShowSort
                    [boSort]="versionSort"
                    boSortKey="source"
                    nzWidth="200px"
                    i18n="@@library.workflow.col.source"
                  >
                    Published from
                  </th>
                  <th
                    nzShowSort
                    [boSort]="versionSort"
                    boSortKey="published"
                    nzWidth="210px"
                    i18n="@@library.workflow.col.published"
                  >
                    Published
                  </th>
                </tr>
              </thead>
              <tbody>
                @for (v of versionRows(); track v.version) {
                  <tr
                    class="clickable-row"
                    tabindex="0"
                    (click)="showGraph(v.version)"
                    (keydown.enter)="showGraph(v.version)"
                  >
                    <td class="mono-cell">v{{ v.version }}</td>
                    <td>{{ v.changeNote || '-' }}</td>
                    <td nzAlign="right" class="mono-cell">{{ v.nodeCount }}</td>
                    <td class="mono-cell small">
                      {{ v.source?.projectKey ?? '-'
                      }}{{ v.source?.workflowVersion ? ' · v' + v.source?.workflowVersion : '' }}
                    </td>
                    <td>
                      <bo-provenance
                        verb="by"
                        i18n-verb="@@library.workflow.by"
                        [actor]="v.publishedBy"
                        [at]="v.publishedAt"
                      />
                    </td>
                  </tr>
                }
              </tbody>
            </nz-table>
          </nz-tab>

          <nz-tab [nzTitle]="projectsTitle()">
            @if (!w.adoptions.length) {
              <bo-empty
                tone="quiet"
                title="No project you can see uses this workflow."
                i18n-title="@@library.workflow.noAdoptions"
                [actionLabel]="adoptProjects().length && !w.deprecated ? text.use : null"
                (action)="adoptOpen.set(true)"
              />
            } @else {
              <nz-table [nzData]="adoptionRows()" [nzShowPagination]="false" nzSize="middle">
                <thead>
                  <tr>
                    <th
                      nzShowSort
                      [boSort]="adoptionSort"
                      boSortKey="project"
                      nzWidth="136px"
                      i18n="@@library.workflow.col.project"
                    >
                      Project
                    </th>
                    <th
                      nzShowSort
                      [boSort]="adoptionSort"
                      boSortKey="workflow"
                      i18n="@@library.workflow.col.workflow"
                    >
                      Workflow
                    </th>
                    <th
                      nzShowSort
                      [boSort]="adoptionSort"
                      boSortKey="follows"
                      nzWidth="260px"
                      i18n="@@library.workflow.col.follows"
                    >
                      Follows
                    </th>
                    <th
                      nzShowSort
                      [boSort]="adoptionSort"
                      boSortKey="state"
                      nzWidth="110px"
                      i18n="@@library.workflow.col.state"
                    >
                      State
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @for (a of adoptionRows(); track a.workflowId) {
                    <tr>
                      <td>
                        <a class="mono-cell" [routerLink]="['/projects', a.projectId]">{{
                          a.projectKey
                        }}</a>
                      </td>
                      <td>
                        <a [routerLink]="['/workflows', a.workflowId, 'edit']">{{
                          a.workflowName
                        }}</a>
                      </td>
                      <td>
                        <span class="mono small">v{{ a.version }}</span>
                        @if (behind(a)) {
                          <span class="chip outline" i18n="@@library.workflow.behind"
                            >update available</span
                          >
                        }
                        @if (a.modified) {
                          <span class="chip outline" i18n="@@library.workflow.modified"
                            >changed locally</span
                          >
                        }
                      </td>
                      <td class="small">{{ a.enabled ? text.enabled : text.disabled }}</td>
                    </tr>
                  }
                </tbody>
              </nz-table>
            }
          </nz-tab>
        </nz-tabs>

        <app-adopt-workflow-dialog
          [open]="adoptOpen()"
          [fixedLibraryWorkflowId]="w.id"
          [projects]="adoptProjects()"
          (adopted)="onAdopted($event)"
          (closed)="adoptOpen.set(false)"
        />

        <nz-modal
          [nzVisible]="editOpen()"
          nzTitle="Library workflow details"
          i18n-nzTitle="@@library.workflow.edit.title"
          nzOkText="Save"
          i18n-nzOkText="@@library.workflow.edit.save"
          nzCancelText="Cancel"
          i18n-nzCancelText="@@library.workflow.edit.cancel"
          [nzOkDisabled]="!draftName().trim()"
          [nzOkLoading]="saving()"
          (nzOnOk)="saveDetails()"
          (nzOnCancel)="editOpen.set(false)"
        >
          <ng-container *nzModalContent>
            <label class="field-label" for="lw-name" i18n="@@library.workflow.edit.name"
              >Name</label
            >
            <input
              id="lw-name"
              nz-input
              [ngModel]="draftName()"
              (ngModelChange)="draftName.set($event)"
            />
            <label class="field-label" for="lw-desc" i18n="@@library.workflow.edit.description"
              >Description</label
            >
            <textarea
              id="lw-desc"
              nz-input
              [ngModel]="draftDescription()"
              (ngModelChange)="draftDescription.set($event)"
              [nzAutosize]="{ minRows: 2, maxRows: 6 }"
            ></textarea>
            <label class="field-label" for="lw-tags" i18n="@@library.workflow.edit.tags"
              >Tags</label
            >
            <nz-select
              id="lw-tags"
              nzMode="tags"
              style="width: 100%"
              [ngModel]="draftTags()"
              (ngModelChange)="draftTags.set($event)"
            ></nz-select>
            <label class="switch-row">
              <nz-switch
                [ngModel]="draftDeprecated()"
                (ngModelChange)="draftDeprecated.set($event)"
              />
              <span i18n="@@library.workflow.edit.deprecated"
                >Deprecated: hidden from "Add from library"; projects that use it keep running
                it</span
              >
            </label>
          </ng-container>
        </nz-modal>
      }
    </div>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        align-items: center;
      }
      .small {
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .periods {
        display: flex;
        justify-content: flex-end;
        gap: 4px;
        margin-bottom: 12px;
      }
      .period {
        height: 28px;
        padding: 0 12px;
        border-radius: 999px;
        border: 1px solid var(--bo-line-strong);
        background: var(--bo-surface);
        color: var(--bo-ink-2);
        font-size: 12.5px;
        cursor: pointer;
      }
      .period.on {
        background: var(--bo-ink);
        border-color: var(--bo-ink);
        color: var(--bo-on-ink);
      }
      .period:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .graph-bar {
        display: flex;
        gap: 8px;
        align-items: center;
        margin-bottom: 10px;
        font-size: 12.5px;
      }
      .canvas-wrap {
        height: 62vh;
        min-height: 420px;
        padding: 6px;
      }
      .canvas-wrap app-workflow-canvas {
        height: 100%;
      }
      .field-label {
        display: block;
        margin: 12px 0 4px;
        font-size: 12.5px;
        font-weight: 500;
      }
      .switch-row {
        display: flex;
        gap: 10px;
        align-items: center;
        margin-top: 16px;
        font-size: 13px;
      }
    `,
  ],
})
export class LibraryWorkflowPageComponent {
  private readonly api = inject(WorkflowLibraryApi);
  private readonly insightsApi = inject(InsightsApi);
  private readonly identity = inject(IdentityService);
  private readonly store = inject(ProjectsStore);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly crumbs = inject(BreadcrumbService);
  readonly router = inject(Router);

  readonly libraryWorkflowId = input.required<string>();
  readonly f = urlFilters({ period: 'string', tab: 'string' });
  readonly periods = PERIODS;

  readonly entry = signal<LibraryWorkflow | null>(null);
  readonly missing = signal(false);
  readonly insights = signal<WorkflowInsights | null>(null);
  readonly metricsLoading = signal(false);
  readonly graphVersion = signal<number | null>(null);
  readonly versionDetail = signal<LibraryWorkflowVersionDetail | null>(null);
  readonly adoptOpen = signal(false);
  readonly editOpen = signal(false);
  readonly saving = signal(false);
  readonly draftName = signal('');
  readonly draftDescription = signal('');
  readonly draftTags = signal<string[]>([]);
  readonly draftDeprecated = signal(false);

  readonly text = {
    use: $localize`:@@library.workflow.use:Use in a project`,
    enabled: $localize`:@@library.workflow.enabled:enabled`,
    disabled: $localize`:@@library.workflow.disabled:disabled`,
    reload: $localize`:@@library.workflow.reload:Reload`,
    editDetails: $localize`:@@library.workflow.editDetails:Edit details`,
    delete: $localize`:@@library.workflow.delete:Delete from the library`,
  };

  readonly period = computed<PeriodId>(() => (this.f.values().period as PeriodId) || '30d');
  readonly tabIndex = computed(() =>
    Math.max(0, TABS.indexOf((this.f.values().tab ?? 'performance') as (typeof TABS)[number])),
  );
  readonly canManage = computed(() => this.identity.can('library.manage'));
  readonly adoptProjects = computed(() =>
    this.store.projects().filter((p) => this.identity.canInProject('workflow.edit', p.id)),
  );
  readonly menu = computed(() => [
    { label: this.text.reload, icon: 'reload', run: () => this.load() },
    ...(this.canManage()
      ? [
          { label: this.text.editDetails, icon: 'edit', run: () => this.openEdit() },
          { label: this.text.delete, icon: 'delete', run: () => void this.remove(), danger: true },
        ]
      : []),
  ]);
  readonly usageText = computed(() => {
    const u = this.entry()?.usage;
    return icu(
      $localize`:@@library.workflow.usage:Used by {workflows, plural, one {# workflow} other {# workflows}} in {projects, plural, one {# project} other {# projects}} you can see`,
      { workflows: u?.workflows ?? 0, projects: u?.projects ?? 0 },
    );
  });
  readonly versionsTitle = computed(
    () =>
      $localize`:@@library.workflow.tab.versions:Versions` +
      ' ' +
      (this.entry()?.versions.length ?? 0),
  );
  readonly projectsTitle = computed(
    () =>
      $localize`:@@library.workflow.tab.projects:Projects` +
      ' ' +
      (this.entry()?.adoptions.length ?? 0),
  );
  /**
   * Column sorting of the versions (URL `?sort=`) and the projects tab (`?adoptionSort=`); the metrics panel keeps
   * its own parameters.
   */
  readonly versionSort = tableSort<LibraryWorkflow['versions'][number]>({
    version: (v) => v.version,
    changes: (v) => v.changeNote,
    nodes: (v) => v.nodeCount,
    source: (v) => v.source?.projectKey,
    published: (v) => v.publishedAt,
  });
  readonly adoptionSort = tableSort<LibraryWorkflowAdoption>(
    {
      project: (a) => a.projectKey,
      workflow: (a) => a.workflowName,
      follows: (a) => a.version,
      state: (a) => !!a.enabled,
    },
    { param: 'adoptionSort' },
  );
  readonly versionRows = computed(() => this.versionSort.apply(this.entry()?.versions ?? []));
  readonly adoptionRows = computed(() => this.adoptionSort.apply(this.entry()?.adoptions ?? []));
  /** The chosen version's graph in the shape the canvas reads. */
  readonly definition = computed<WorkflowDefinitionInput | null>(() => {
    const v = this.versionDetail();
    if (!v) return null;
    return {
      name: v.name,
      description: v.description ?? '',
      nodes: v.nodes,
      edges: v.edges,
      triggers: v.triggers ?? [],
      enabled: true,
      canvas: v.canvas ?? null,
    };
  });

  constructor() {
    this.store.ensure();
    effect(() => {
      this.libraryWorkflowId();
      untracked(() => this.load());
    });
    effect(() => {
      this.period();
      const id = this.libraryWorkflowId();
      untracked(() => this.loadMetrics(id));
    });
    effect(() => {
      const version = this.graphVersion();
      const id = this.libraryWorkflowId();
      if (!version) return;
      untracked(() => {
        this.versionDetail.set(null);
        this.api.version(id, version).subscribe((v) => this.versionDetail.set(v));
      });
    });
  }

  load(): void {
    this.api.get(this.libraryWorkflowId()).subscribe({
      next: (w) => {
        this.entry.set(w);
        this.missing.set(false);
        if (!this.graphVersion()) this.graphVersion.set(w.latestVersion);
        this.crumbs.set([
          {
            label: $localize`:@@library.workflow.crumb.library:Library`,
            link: '/library/templates',
          },
          {
            label: $localize`:@@library.workflow.crumb.workflows:Workflows`,
            link: '/library/workflows',
          },
          { label: w.name },
        ]);
      },
      error: () => this.missing.set(true),
    });
  }

  loadMetrics(id: string): void {
    this.metricsLoading.set(true);
    this.insightsApi
      .workflow({ libraryWorkflowId: id }, periodRange(this.period()))
      .pipe(catchError(() => of(null)))
      .subscribe((i) => {
        this.insights.set(i);
        this.metricsLoading.set(false);
      });
  }

  setTab(index: number): void {
    this.f.set({ tab: index === 0 ? null : TABS[index] });
  }

  showGraph(version: number): void {
    this.graphVersion.set(version);
    this.setTab(TABS.indexOf('graph'));
  }

  behind(a: LibraryWorkflowAdoption): boolean {
    return updateAvailable({
      version: a.version ?? 0,
      latestVersion: this.entry()?.latestVersion ?? 0,
    });
  }

  onAdopted(definition: WorkflowDefinition): void {
    this.adoptOpen.set(false);
    void this.router.navigate(['/workflows', definition.id, 'edit']);
  }

  openEdit(): void {
    const w = this.entry();
    if (!w) return;
    this.draftName.set(w.name);
    this.draftDescription.set(w.description ?? '');
    this.draftTags.set([...(w.tags ?? [])]);
    this.draftDeprecated.set(!!w.deprecated);
    this.editOpen.set(true);
  }

  saveDetails(): void {
    const w = this.entry();
    if (!w) return;
    this.saving.set(true);
    this.api
      .update(w.id, {
        name: this.draftName().trim(),
        description: this.draftDescription().trim(),
        tags: this.draftTags(),
        deprecated: this.draftDeprecated(),
      })
      .subscribe({
        next: (saved) => {
          this.saving.set(false);
          this.editOpen.set(false);
          this.entry.set(saved);
          this.message.success($localize`:@@library.workflow.saved:Library workflow saved`);
        },
        error: () => this.saving.set(false),
      });
  }

  async remove(): Promise<void> {
    const w = this.entry();
    if (!w) return;
    const ok = await this.confirm.danger(
      $localize`:@@library.workflow.deleteConfirm.title:Delete "${w.key}:key:" from the library?`,
      $localize`:@@library.workflow.deleteConfirm.content:All its versions are removed. This is refused while any project workflow is linked to it: deprecate it instead, or detach the copies first. Past runs keep their metrics.`,
      this.text.delete,
    );
    if (!ok) return;
    this.api.delete(w.id).subscribe(() => {
      this.message.success($localize`:@@library.workflow.deleted:Library workflow deleted`);
      void this.router.navigate(['/library/workflows']);
    });
  }
}

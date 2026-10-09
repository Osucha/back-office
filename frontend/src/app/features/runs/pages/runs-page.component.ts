import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
  DestroyRef,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { catchError, of } from 'rxjs';
import { RUN_STATUSES, type RunStatus, type WorkflowDefinition } from '../../../core/api/types';
import { WorkflowsApi } from '../../../core/api/workflows.service';
import { ProjectsStore } from '../../../core/state/projects.store';
import { HOLDER_NAME, type BatonHolder } from '../../../core/ui/relay/baton';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import {
  FilterBarComponent,
  type FilterChip,
  type FilterPreset,
} from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { humanize } from '../../../core/ui/status-tag.component';
import { RunsTableComponent } from '../components/runs-table.component';
import { RUN_HOLDERS } from '../data/run-filters';

/** Runs: every workflow execution, live; filters (holder, project, workflow, starter, search) live in the URL. */
@Component({
  selector: 'app-runs-page',
  imports: [
    FormsModule,
    NzSelectModule,
    FilterBarComponent,
    PageHeaderComponent,
    RunsTableComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="runs"
        title="Runs"
        i18n-title="@@runs.list.title"
        subtitle="Every workflow execution, live. Who holds the baton is on the left."
        i18n-subtitle="@@runs.list.subtitle"
      />

      <bo-filter-bar
        #bar
        placeholder="Search issue key, run id or workflow"
        i18n-placeholder="@@runs.list.search"
        label="Filter runs"
        i18n-label="@@runs.list.filterLabel"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [holders]="holders"
        [selectedHolders]="f.values().holder"
        (selectedHoldersChange)="f.set({ holder: $event })"
        [chips]="chips()"
        (chipRemoved)="removeChip($event)"
        (cleared)="f.clear()"
        [presets]="presets()"
        (presetSelected)="applyPreset($any($event))"
      >
        <nz-select
          [ngModel]="f.values().projectId"
          (ngModelChange)="f.set({ projectId: $event, workflowId: null })"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="All projects"
          i18n-nzPlaceHolder="@@runs.list.allProjects"
          style="width: 190px"
          aria-label="Filter by project"
          i18n-aria-label="@@runs.list.byProject"
        >
          @for (p of store.projects(); track p.id) {
            <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' · ' + p.name" />
          }
        </nz-select>
        @if (f.values().projectId) {
          <nz-select
            [ngModel]="f.values().workflowId"
            (ngModelChange)="f.set({ workflowId: $event })"
            nzAllowClear
            nzShowSearch
            nzPlaceHolder="All workflows"
            i18n-nzPlaceHolder="@@runs.list.allWorkflows"
            style="width: 200px"
            aria-label="Filter by workflow"
            i18n-aria-label="@@runs.list.byWorkflow"
          >
            @for (w of workflows(); track w.id) {
              <nz-option [nzValue]="w.id" [nzLabel]="w.name" />
            }
          </nz-select>
        }
      </bo-filter-bar>

      <app-runs-table
        [projectId]="f.values().projectId"
        [workflowId]="f.values().workflowId"
        [status]="statuses()"
        [holders]="f.values().holder"
        [query]="f.values().q"
        [startedBy]="f.values().startedBy"
        [emptyText]="f.active() ? text.noMatch : text.noRuns"
        [emptyActionLabel]="f.active() ? text.clearFilters : text.goToProjects"
        [emptyAction]="emptyAction"
      />
    </div>
  `,
})
export class RunsPageComponent {
  private readonly router = inject(Router);
  private readonly workflowsApi = inject(WorkflowsApi);
  readonly store = inject(ProjectsStore);

  readonly f = urlFilters({
    q: 'string',
    holder: 'list',
    projectId: 'string',
    workflowId: 'string',
    startedBy: 'string',
    status: 'list',
  });
  readonly holders: BatonHolder[] = RUN_HOLDERS;
  readonly workflows = signal<WorkflowDefinition[]>([]);
  private readonly bar = viewChild<FilterBarComponent>('bar');

  /** Legacy `?status=` links keep working. */
  readonly statuses = computed(() =>
    this.f.values().status.filter((s): s is RunStatus => (RUN_STATUSES as string[]).includes(s)),
  );

  readonly text = {
    noMatch: $localize`:@@runs.list.noMatch:No runs match these filters.`,
    noRuns: $localize`:@@runs.list.noRuns:No runs yet. Start one from a project workflow.`,
    clearFilters: $localize`:@@runs.list.clearFilters:Clear filters`,
    goToProjects: $localize`:@@runs.list.goToProjects:Go to projects`,
  };

  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    const selected = $localize`:@@runs.list.chip.selected:selected`;
    if (v.projectId)
      out.push({
        id: 'projectId',
        label: $localize`:@@runs.list.chip.project:Project: ${this.store.byId().get(v.projectId)?.key ?? selected}:project:`,
      });
    if (v.workflowId)
      out.push({
        id: 'workflowId',
        label: $localize`:@@runs.list.chip.workflow:Workflow: ${this.workflows().find((w) => w.id === v.workflowId)?.name ?? selected}:workflow:`,
      });
    for (const s of this.statuses())
      out.push({
        id: 'status:' + s,
        label: $localize`:@@runs.list.chip.status:Status: ${humanize(s)}:status:`,
      });
    if (v.startedBy === 'me')
      out.push({ id: 'startedBy', label: $localize`:@@runs.list.chip.startedByMe:Started by me` });
    return out;
  });

  readonly presets = computed<FilterPreset[]>(() => {
    const v = this.f.values();
    const is = (list: string[]) =>
      v.holder.length === list.length && list.every((h) => v.holder.includes(h));
    return [
      {
        label: $localize`:@@runs.list.preset.inFlight:In flight`,
        value: { holder: ['agent', 'ci'] },
        active: is(['agent', 'ci']),
      },
      {
        label: $localize`:@@runs.list.preset.failed:Failed`,
        value: { holder: ['blocked'] },
        active: is(['blocked']),
      },
      {
        label: $localize`:@@runs.list.preset.startedByMe:Started by me`,
        value: { startedBy: v.startedBy === 'me' ? null : 'me' },
        active: v.startedBy === 'me',
      },
    ];
  });

  readonly emptyAction = () =>
    this.f.active() ? this.f.clear() : void this.router.navigate(['/projects']);

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@runs.list.crumb:Runs` }]);
    this.store.ensure();
    effect(() => {
      const id = this.f.values().projectId;
      untracked(() => {
        this.workflows.set([]);
        if (id)
          this.workflowsApi
            .listForProject(id)
            .pipe(catchError(() => of([])))
            .subscribe((w) => this.workflows.set(w));
      });
    });
    inject(ShortcutsService).register(
      {
        keys: 'f',
        description: $localize`:@@runs.list.shortcut.search:Search runs`,
        group: $localize`:@@runs.list.shortcut.group:Runs`,
        handler: () => this.bar()?.focus(),
      },
      inject(DestroyRef),
    );
  }

  applyPreset(value: Record<string, unknown>): void {
    if ('holder' in value) {
      const target = value['holder'] as string[];
      const cur = this.f.values().holder;
      const same = cur.length === target.length && target.every((h) => cur.includes(h));
      this.f.set({ holder: same ? [] : target });
    } else this.f.set(value);
  }

  removeChip(id: string): void {
    const v = this.f.values();
    if (id.startsWith('holder:')) this.f.set({ holder: v.holder.filter((h) => h !== id.slice(7)) });
    else if (id.startsWith('status:'))
      this.f.set({ status: v.status.filter((s) => s !== id.slice(7)) });
    else if (id === 'projectId') this.f.set({ projectId: null, workflowId: null });
    else this.f.set({ [id]: null });
  }
}

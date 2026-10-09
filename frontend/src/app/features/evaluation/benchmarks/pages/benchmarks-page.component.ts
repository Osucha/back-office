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
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { debounceTime } from 'rxjs';
import { BenchmarksApi } from '../../../../core/api/benchmarks.service';
import { ModelProfilesApi } from '../../../../core/api/model-profiles.service';
import { TaskSuitesApi } from '../../../../core/api/task-suites.service';
import type {
  BenchmarkInput,
  BenchmarkSummary,
  EvalTask,
  ModelProfile,
  TaskSuiteSummary,
} from '../../../../core/api/types';
import { IdentityService } from '../../../../core/auth/identity.service';
import { EventStreamService } from '../../../../core/events/event-stream.service';
import type { BatonHolder } from '../../../../core/ui/relay/baton';
import { BatonComponent } from '../../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../../core/ui/relay/breadcrumb.service';
import { CostComponent } from '../../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../../core/ui/relay/empty.component';
import {
  FilterBarComponent,
  type FilterChip,
} from '../../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../../core/ui/relay/filter-state';
import { listNavigation } from '../../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../../core/ui/relay/shortcuts.service';
import { SidePanelComponent } from '../../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../../core/ui/relay/skeleton.component';
import { RELAY_TABLE_STYLES } from '../../../../core/ui/relay/table-styles';
import { humanize } from '../../../../core/ui/status-tag.component';
import { icu } from '../../../../core/i18n/icu';

import { EvaluationNavComponent } from '../../components/evaluation-nav.component';
import { AttemptStripComponent } from '../components/attempt-strip.component';
import { BENCHMARK_HOLDERS, benchmarkBaton, benchmarkHolder } from '../data/benchmark-view';
import { categoryLabel } from '../../suites/data/suite-view';

/**
 * Evaluation: benchmarks. Holder, suite and search filters live in the URL; attempt progress is
 * a baton-coloured strip. `?create=1` (optionally `&suiteId=`) opens the create panel pre-filled.
 */
@Component({
  selector: 'app-benchmarks-page',
  imports: [
    FormsModule,
    RouterLink,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzSelectModule,
    NzRadioModule,
    AttemptStripComponent,
    BatonComponent,
    CostComponent,
    EmptyComponent,
    EvaluationNavComponent,
    FilterBarComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SidePanelComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="benchmarks"
        title="Evaluation"
        i18n-title="@@evaluation.title"
        subtitle="Benchmarks run a suite against several models, scored by one fixed judge. Results inform the model choice per step."
        i18n-subtitle="@@evaluation.benchmarks.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        @if (canRun()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="openCreate()">
            <nz-icon nzType="plus" />
            <ng-container i18n="@@evaluation.benchmarks.create">Create benchmark</ng-container>
          </button>
        }
        <app-evaluation-nav
          boHeaderBelow
          active="benchmarks"
          [counts]="{ benchmarks: benchmarks().length }"
        />
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search benchmarks"
        i18n-placeholder="@@evaluation.benchmarks.search"
        label="Filter benchmarks"
        i18n-label="@@evaluation.benchmarks.filter"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [holders]="holders"
        [selectedHolders]="f.values().holder"
        (selectedHoldersChange)="f.set({ holder: $event })"
        [chips]="chips()"
        (chipRemoved)="f.set({ suite: null })"
        (cleared)="f.clear()"
      >
        <nz-select
          [ngModel]="f.values().suite"
          (ngModelChange)="f.set({ suite: $event })"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="All suites"
          i18n-nzPlaceHolder="@@evaluation.benchmarks.allSuites"
          style="width: 200px"
          aria-label="Filter by task suite"
          i18n-aria-label="@@evaluation.benchmarks.filterSuite"
        >
          @for (s of suites(); track s.id) {
            <nz-option [nzValue]="s.id" [nzLabel]="s.name" />
          }
        </nz-select>
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="5"
          [columns]="6"
          label="Loading benchmarks"
          i18n-label="@@evaluation.benchmarks.loading"
        />
      } @else if (!filtered().length) {
        @if (f.active()) {
          <bo-empty
            title="No benchmark matches these filters."
            i18n-title="@@evaluation.benchmarks.noMatch"
            actionLabel="Clear filters"
            i18n-actionLabel="@@evaluation.clearFilters"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No benchmarks yet."
            i18n-title="@@evaluation.benchmarks.empty"
            detail="Compare models on one of your task suites: same tasks, same judge, cost and score side by side."
            i18n-detail="@@evaluation.benchmarks.emptyDetail"
            [actionLabel]="canRun() ? text.create : null"
            (action)="openCreate()"
            help="benchmarks"
            guideLabel="How benchmarks work"
            i18n-guideLabel="@@evaluation.benchmarks.guide"
          />
        }
      } @else {
        <nz-table
          #t
          [nzData]="filtered()"
          [nzShowPagination]="filtered().length > 50"
          [nzPageSize]="50"
          nzSize="middle"
        >
          <thead>
            <tr>
              <th nzWidth="120px" i18n="@@evaluation.benchmarks.col.holder">Holder</th>
              <th i18n="@@evaluation.benchmarks.col.benchmark">Benchmark</th>
              <th nzWidth="170px" i18n="@@evaluation.benchmarks.col.suite">Suite</th>
              <th nzWidth="70px" nzAlign="right" i18n="@@evaluation.benchmarks.col.models">
                Models
              </th>
              <th nzWidth="250px" i18n="@@evaluation.benchmarks.col.attempts">Attempts</th>
              <th nzWidth="90px" nzAlign="right" i18n="@@evaluation.benchmarks.col.cost">Cost</th>
              <th nzWidth="190px" i18n="@@evaluation.benchmarks.col.created">Created</th>
            </tr>
          </thead>
          <tbody>
            @for (b of t.data; track b.id; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i"
                [attr.data-nav-index]="i"
                tabindex="-1"
                (click)="open(b)"
              >
                <td><bo-baton [baton]="baton(b)" size="sm" /></td>
                <td>
                  <div class="cell-main">
                    <a
                      class="cell-title"
                      [routerLink]="['/evaluation/benchmarks', b.id]"
                      (click)="$event.stopPropagation()"
                      >{{ b.name }}</a
                    >
                    <span class="cell-sub"
                      >{{ repetitions(b.repetitions) }} · {{ harness(b.codingHarness) }}</span
                    >
                  </div>
                </td>
                <td class="ink-2">{{ b.suiteName }}</td>
                <td nzAlign="right">
                  <span class="mono-cell">{{ b.modelProfileIds.length }}</span>
                </td>
                <td><app-attempt-strip [progress]="b.progress" [status]="b.status" /></td>
                <td nzAlign="right"><bo-cost [value]="b.totalCost" /></td>
                <td>
                  <bo-provenance [actor]="b.createdBy" [at]="b.createdAt" />
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>

    <bo-side-panel
      [open]="creating()"
      title="Create benchmark"
      i18n-title="@@evaluation.benchmarks.create"
      subtitle="A suite × models × repetitions, scored by one judge"
      i18n-subtitle="@@evaluation.benchmarks.form.subtitle"
      [defaultWidth]="600"
      storageKey="backoffice.panelWidth.benchmark"
      bodyLabel="Benchmark settings"
      i18n-bodyLabel="@@evaluation.benchmarks.form.bodyLabel"
      initialFocus="#b-name"
      (closed)="closeCreate()"
    >
      <form
        nz-form
        nzLayout="vertical"
        id="bench-form"
        (submit)="$event.preventDefault(); createBenchmark()"
      >
        <nz-form-item>
          <nz-form-label nzRequired nzFor="b-name" i18n="@@evaluation.benchmarks.form.name"
            >Name</nz-form-label
          >
          <nz-form-control
            ><input
              nz-input
              id="b-name"
              name="name"
              [(ngModel)]="form.name"
              placeholder="Sept 2026: frontier vs mid-tier"
              i18n-placeholder="@@evaluation.benchmarks.form.namePlaceholder"
          /></nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzRequired nzFor="b-suite" i18n="@@evaluation.benchmarks.form.suite"
            >Task suite</nz-form-label
          >
          <nz-form-control>
            <nz-select
              id="b-suite"
              name="suiteId"
              [ngModel]="form.suiteId"
              (ngModelChange)="selectSuite($event)"
              nzShowSearch
              nzPlaceHolder="Select a suite"
              i18n-nzPlaceHolder="@@evaluation.benchmarks.form.selectSuite"
            >
              @for (s of suites(); track s.id) {
                <nz-option [nzValue]="s.id" [nzLabel]="suiteOption(s)" />
              }
            </nz-select>
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzFor="b-tasks" i18n="@@evaluation.benchmarks.form.tasks"
            >Tasks</nz-form-label
          >
          <nz-form-control
            nzExtra="Leave empty to run every task of the suite."
            i18n-nzExtra="@@evaluation.benchmarks.form.tasksHint"
          >
            <nz-select
              id="b-tasks"
              name="taskIds"
              [(ngModel)]="form.taskIds"
              nzMode="multiple"
              nzAllowClear
              [nzPlaceHolder]="form.suiteId ? text.allTasks : text.pickSuite"
              [nzNotFoundContent]="text.pickSuite"
              [nzLoading]="tasksLoading()"
            >
              @for (t of suiteTasks(); track t.id) {
                <nz-option
                  [nzValue]="t.id"
                  [nzLabel]="
                    t.name + ' (' + category(t.category) + ', ' + label(t.difficulty) + ')'
                  "
                />
              }
            </nz-select>
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzRequired nzFor="b-models" i18n="@@evaluation.benchmarks.form.models"
            >Candidate models</nz-form-label
          >
          <nz-form-control
            nzExtra="Profiles whose provider has no API key cannot be picked."
            i18n-nzExtra="@@evaluation.benchmarks.form.modelsHint"
          >
            <nz-select
              id="b-models"
              name="modelProfileIds"
              [(ngModel)]="form.modelProfileIds"
              nzMode="multiple"
              nzShowSearch
              nzPlaceHolder="Pick at least one"
              i18n-nzPlaceHolder="@@evaluation.benchmarks.form.pickOne"
            >
              @for (p of profiles(); track p.id) {
                <nz-option
                  [nzValue]="p.id"
                  [nzLabel]="p.displayName"
                  [nzDisabled]="p.providerConfigured === false"
                />
              }
            </nz-select>
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzRequired nzFor="b-judge" i18n="@@evaluation.benchmarks.form.judge"
            >Judge model</nz-form-label
          >
          <nz-form-control
            nzExtra="Fixed for the whole benchmark so scores stay comparable."
            i18n-nzExtra="@@evaluation.benchmarks.form.judgeHint"
          >
            <nz-select
              id="b-judge"
              name="judgeModelProfileId"
              [(ngModel)]="form.judgeModelProfileId"
              nzShowSearch
              nzPlaceHolder="Select the judge"
              i18n-nzPlaceHolder="@@evaluation.benchmarks.form.selectJudge"
            >
              @for (p of profiles(); track p.id) {
                <nz-option
                  [nzValue]="p.id"
                  [nzLabel]="p.displayName"
                  [nzDisabled]="p.providerConfigured === false"
                />
              }
            </nz-select>
          </nz-form-control>
        </nz-form-item>
        <div class="grid">
          <nz-form-item>
            <nz-form-label nzFor="b-reps" i18n="@@evaluation.benchmarks.form.repetitions"
              >Repetitions</nz-form-label
            >
            <nz-form-control
              nzExtra="1-5; more repetitions measure stability."
              i18n-nzExtra="@@evaluation.benchmarks.form.repetitionsHint"
              ><nz-input-number
                id="b-reps"
                name="repetitions"
                [(ngModel)]="form.repetitions"
                [nzMin]="1"
                [nzMax]="5"
                [nzPrecision]="0"
                style="width: 100%"
            /></nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label i18n="@@evaluation.benchmarks.form.harness"
              >Coding harness</nz-form-label
            >
            <nz-form-control
              nzExtra="OpenCode works with every provider; Claude Code with Anthropic only."
              i18n-nzExtra="@@evaluation.benchmarks.form.harnessHint"
            >
              <nz-radio-group
                name="codingHarness"
                [(ngModel)]="form.codingHarness"
                aria-label="Coding harness"
                i18n-aria-label="@@evaluation.benchmarks.form.harness"
              >
                <label nz-radio-button nzValue="OPENCODE">OpenCode</label>
                <label nz-radio-button nzValue="CLAUDE_CODE">Claude Code</label>
              </nz-radio-group>
            </nz-form-control>
          </nz-form-item>
        </div>
        <p class="matrix">{{ matrixText() }}</p>
      </form>
      <div boPanelFooter class="foot">
        <button
          nz-button
          type="button"
          (click)="closeCreate()"
          i18n="@@evaluation.benchmarks.form.cancel"
        >
          Cancel
        </button>
        <span class="spacer"></span>
        <button
          nz-button
          type="submit"
          form="bench-form"
          [nzLoading]="saving() === 'draft'"
          [disabled]="!valid()"
          i18n="@@evaluation.benchmarks.form.saveDraft"
        >
          Save as draft
        </button>
        <button
          nz-button
          nzType="primary"
          type="button"
          (click)="createBenchmark(true)"
          [nzLoading]="saving() === 'start'"
          [disabled]="!valid()"
        >
          <nz-icon nzType="caret-right" />
          <ng-container i18n="@@evaluation.benchmarks.form.createStart"
            >Create and start</ng-container
          >
        </button>
      </div>
    </bo-side-panel>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 0 16px;
      }
      .matrix {
        margin: 4px 0 0;
        padding: 10px 12px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .matrix .mono {
        font-size: 13px;
        font-weight: 600;
        color: var(--bo-ink);
      }
      .foot {
        display: flex;
        gap: 8px;
        align-items: center;
      }
      .spacer {
        flex: 1;
      }
    `,
  ],
})
export class BenchmarksPageComponent {
  private readonly api = inject(BenchmarksApi);
  private readonly suitesApi = inject(TaskSuitesApi);
  private readonly profilesApi = inject(ModelProfilesApi);
  private readonly events = inject(EventStreamService);
  private readonly message = inject(NzMessageService);
  private readonly router = inject(Router);
  private readonly identity = inject(IdentityService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly bar = viewChild<FilterBarComponent>('bar');

  /** Query params: `?suiteId=...&create=1` opens the form pre-filled (from the suite page). */
  readonly suiteId = input<string | undefined>();
  readonly create = input<string | undefined>();

  readonly f = urlFilters({ q: 'string', holder: 'list', suite: 'string' });
  readonly holders: BatonHolder[] = BENCHMARK_HOLDERS;
  readonly benchmarks = signal<BenchmarkSummary[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly creating = signal(false);
  readonly saving = signal<'draft' | 'start' | null>(null);
  readonly suites = signal<TaskSuiteSummary[]>([]);
  readonly profiles = signal<ModelProfile[]>([]);
  readonly suiteTasks = signal<EvalTask[]>([]);
  readonly tasksLoading = signal(false);
  readonly canRun = computed(() => this.identity.can('evaluation.run'));
  readonly baton = (b: BenchmarkSummary) => benchmarkBaton(b.status);
  readonly label = (v: string | null | undefined) => humanize(v).toLowerCase();
  readonly category = (v: string | null | undefined) => categoryLabel(v).toLowerCase();
  readonly text = {
    reload: $localize`:@@evaluation.benchmarks.reload:Reload benchmarks`,
    create: $localize`:@@evaluation.benchmarks.createAction:Create benchmark`,
    allTasks: $localize`:@@evaluation.benchmarks.form.allTasks:All tasks`,
    pickSuite: $localize`:@@evaluation.benchmarks.form.pickSuite:Pick a suite first`,
  };

  readonly filtered = computed(() => {
    const v = this.f.values();
    const q = (v.q ?? '').trim().toLowerCase();
    return this.benchmarks().filter(
      (b) =>
        (!v.holder.length || v.holder.includes(benchmarkHolder(b))) &&
        (!v.suite || b.suiteId === v.suite) &&
        (!q || [b.name, b.suiteName ?? ''].some((x) => x.toLowerCase().includes(q))),
    );
  });
  readonly chips = computed<FilterChip[]>(() => {
    const s = this.f.values().suite;
    if (!s) return [];
    const suite =
      this.suites().find((x) => x.id === s)?.name ??
      $localize`:@@evaluation.benchmarks.chip.selected:selected`;
    return [
      { id: 'suite', label: $localize`:@@evaluation.benchmarks.chip.suite:Suite: ${suite}:suite:` },
    ];
  });

  form: {
    name: string;
    suiteId: string | null;
    taskIds: string[];
    modelProfileIds: string[];
    judgeModelProfileId: string | null;
    repetitions: number;
    codingHarness: 'OPENCODE' | 'CLAUDE_CODE';
  } = this.emptyForm();

  readonly nav = listNavigation({
    items: this.filtered,
    open: (b) => this.open(b),
    group: $localize`:@@evaluation.nav.benchmarks:Benchmarks`,
    when: () => !this.creating(),
  });
  readonly reload = () => this.load();

  /** Template-driven form state is a plain object, so these are methods rather than computed signals. */
  valid(): boolean {
    return this.isValid();
  }

  taskCount(): number {
    return this.form.taskIds.length || this.suiteTasks().length;
  }

  matrixSize(): number {
    return this.taskCount() * this.form.modelProfileIds.length * (this.form.repetitions || 1);
  }

  matrixText(): string {
    const tasks = icu(
      $localize`:@@evaluation.benchmarks.form.matrixTasks:{count, plural, one {# task} other {# tasks}}`,
      { count: this.taskCount() },
    );
    const models = icu(
      $localize`:@@evaluation.benchmarks.form.matrixModels:{count, plural, one {# model} other {# models}}`,
      { count: this.form.modelProfileIds.length },
    );
    const reps = this.form.repetitions || 1;
    return icu(
      $localize`:@@evaluation.benchmarks.form.matrix:{count, plural, one {# attempt} other {# attempts}} will run (${tasks}:tasks: × ${models}:models: × ${reps}:repetitions:), each also judged once.`,
      { count: this.matrixSize() },
    );
  }

  repetitions(n: number | null | undefined): string {
    return icu(
      $localize`:@@evaluation.benchmarks.repetitions:{count, plural, one {# repetition} other {# repetitions}}`,
      { count: n ?? 1 },
    );
  }

  suiteOption(s: TaskSuiteSummary): string {
    const tasks = icu(
      $localize`:@@evaluation.benchmarks.form.matrixTasks:{count, plural, one {# task} other {# tasks}}`,
      { count: s.taskCount ?? 0 },
    );
    return `${s.name} (${tasks})`;
  }

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@evaluation.title:Evaluation`, link: '/evaluation/suites' },
      { label: $localize`:@@evaluation.nav.benchmarks:Benchmarks` },
    ]);
    this.load();
    this.suitesApi.list().subscribe((s) => this.suites.set(s));
    this.profilesApi.list(true).subscribe((p) => this.profiles.set(p));
    this.events
      .of('BenchmarkProgress', 'BenchmarkFinished')
      .pipe(debounceTime(2000), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load());
    effect(() => {
      if (!this.create()) return;
      const suite = this.suiteId();
      untracked(() => {
        this.openCreate();
        if (suite) this.selectSuite(suite);
      });
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@evaluation.benchmarks.shortcut.search:Search benchmarks`,
          group: $localize`:@@evaluation.nav.benchmarks:Benchmarks`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@evaluation.benchmarks.createAction:Create benchmark`,
          group: $localize`:@@evaluation.nav.benchmarks:Benchmarks`,
          when: () => this.canRun() && !this.creating(),
          handler: () => this.openCreate(),
        },
      ],
      this.destroyRef,
    );
  }

  harness(h: string | null | undefined): string {
    return h === 'CLAUDE_CODE' ? 'Claude Code' : 'OpenCode';
  }

  load(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (list) => {
        this.benchmarks.set(list);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  open(b: BenchmarkSummary): void {
    void this.router.navigate(['/evaluation/benchmarks', b.id]);
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.suiteTasks.set([]);
    this.creating.set(true);
  }

  closeCreate(): void {
    this.creating.set(false);
    if (this.create())
      void this.router.navigate([], {
        queryParams: { create: null, suiteId: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
  }

  selectSuite(id: string | null): void {
    this.form.suiteId = id;
    this.form.taskIds = [];
    this.suiteTasks.set([]);
    if (!id) return;
    this.tasksLoading.set(true);
    this.suitesApi.get(id).subscribe({
      next: (s) => {
        this.suiteTasks.set(s.tasks ?? []);
        this.tasksLoading.set(false);
        if (!this.form.name)
          this.form.name = `${s.name} - ${new Date().toISOString().slice(0, 10)}`;
      },
      error: () => this.tasksLoading.set(false),
    });
  }

  isValid(): boolean {
    return (
      !!this.form.name.trim() &&
      !!this.form.suiteId &&
      this.form.modelProfileIds.length > 0 &&
      !!this.form.judgeModelProfileId
    );
  }

  createBenchmark(start = false): void {
    if (!this.isValid()) {
      this.message.warning(
        $localize`:@@evaluation.benchmarks.form.required:Name, suite, at least one model and a judge are required`,
      );
      return;
    }
    const input: BenchmarkInput = {
      name: this.form.name.trim(),
      suiteId: this.form.suiteId!,
      modelProfileIds: this.form.modelProfileIds,
      judgeModelProfileId: this.form.judgeModelProfileId!,
      repetitions: this.form.repetitions,
      codingHarness: this.form.codingHarness,
      taskIds: this.form.taskIds,
    };
    this.saving.set(start ? 'start' : 'draft');
    this.api.create(input).subscribe({
      next: (b) => {
        if (start) {
          this.api.start(b.id!).subscribe({
            next: () =>
              this.afterCreate(
                b.id!,
                $localize`:@@evaluation.benchmarks.started:Benchmark started`,
              ),
            error: () =>
              this.afterCreate(
                b.id!,
                $localize`:@@evaluation.benchmarks.startFailed:Benchmark saved as a draft; starting it failed`,
              ),
          });
        } else {
          this.afterCreate(
            b.id!,
            $localize`:@@evaluation.benchmarks.savedDraft:Benchmark saved as a draft`,
          );
        }
      },
      error: () => this.saving.set(null),
    });
  }

  private afterCreate(id: string, msg: string): void {
    this.saving.set(null);
    this.creating.set(false);
    this.message.success(msg);
    void this.router.navigate(['/evaluation/benchmarks', id]);
  }

  private emptyForm() {
    return {
      name: '',
      suiteId: null as string | null,
      taskIds: [] as string[],
      modelProfileIds: [] as string[],
      judgeModelProfileId: null as string | null,
      repetitions: 1,
      codingHarness: 'OPENCODE' as 'OPENCODE' | 'CLAUDE_CODE',
    };
  }
}

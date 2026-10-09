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
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { TaskSuitesApi } from '../../../../core/api/task-suites.service';
import type { EvalTask, EvalTaskInput, TaskSuite } from '../../../../core/api/types';
import { IdentityService } from '../../../../core/auth/identity.service';
import { ConfirmService } from '../../../../core/ui/confirm.service';
import { formatDuration } from '../../../../core/ui/pipes/duration.pipe';
import { BreadcrumbService } from '../../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../../core/ui/relay/empty.component';
import {
  FilterBarComponent,
  type FilterChip,
} from '../../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../../core/ui/relay/filter-state';
import { listNavigation } from '../../../../core/ui/relay/list-nav';
import {
  PageHeaderComponent,
  type PageAction,
} from '../../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../../core/ui/relay/shortcuts.service';
import { panelParam } from '../../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../../core/ui/relay/skeleton.component';
import { RELAY_TABLE_STYLES } from '../../../../core/ui/relay/table-styles';
import { humanize } from '../../../../core/ui/status-tag.component';
import { TaskEditorDrawerComponent } from '../components/task-editor-drawer.component';
import {
  categoryLabel,
  deleteSuiteConsequence,
  filterTasks,
  suiteStats,
  weightsText,
} from '../data/suite-view';

/**
 * Task suite: facts, the task list (search and kind filter in the URL) and the task side panel
 * (`?task=<id>`, `?task=new`).
 */
@Component({
  selector: 'app-suite-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzModalModule,
    NzInputModule,
    NzInputNumberModule,
    NzSelectModule,
    NzTooltipModule,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
    TaskEditorDrawerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      @if (!suite()) {
        @if (notFound()) {
          <bo-empty
            title="This task suite does not exist any more."
            i18n-title="@@evaluation.suite.notFound"
            actionLabel="Go to task suites"
            i18n-actionLabel="@@evaluation.suite.goToList"
            (action)="router.navigate(['/evaluation/suites'])"
          />
        } @else {
          <bo-skeleton
            variant="detail"
            label="Loading the task suite"
            i18n-label="@@evaluation.suite.loading"
          />
        }
      } @else if (suite(); as s) {
        <bo-page-header
          help="suites"
          eyebrow="Task suite"
          i18n-eyebrow="@@evaluation.suite.eyebrow"
          [title]="s.name"
          [subtitle]="s.description ?? null"
          [menu]="menu()"
        >
          <bo-provenance
            boHeaderMeta
            [verb]="s.updatedBy ? text.changedBy : text.created"
            [actor]="s.updatedBy"
            [at]="s.createdAt"
          />
          <a
            boHeaderActions
            nz-button
            [routerLink]="['/evaluation/benchmarks']"
            [queryParams]="{ suiteId: s.id, create: 1 }"
            ><nz-icon nzType="experiment" />
            <ng-container i18n="@@evaluation.suite.benchmarkThis"
              >Benchmark this suite</ng-container
            ></a
          >
          @if (canRun()) {
            <button boHeaderActions nz-button nzType="primary" type="button" (click)="newTask()">
              <nz-icon nzType="plus" />
              <ng-container i18n="@@evaluation.suite.addTask">Add task</ng-container>
            </button>
          }
        </bo-page-header>

        <dl class="facts">
          <div>
            <dt i18n="@@evaluation.suite.tasks">Tasks</dt>
            <dd class="num">{{ stats().tasks }}</dd>
          </div>
          <div>
            <dt i18n="@@evaluation.suite.promptCoding">Prompt / coding</dt>
            <dd class="num">{{ stats().prompt }} / {{ stats().coding }}</dd>
          </div>
          <div>
            <dt i18n="@@evaluation.suite.codingScore">Coding score</dt>
            <dd>{{ weights(s) }}</dd>
          </div>
          <div>
            <dt i18n="@@evaluation.suite.totalWeight">Total weight</dt>
            <dd class="num">{{ stats().totalWeight }}</dd>
          </div>
        </dl>

        <bo-filter-bar
          #bar
          placeholder="Search tasks, categories or repositories"
          i18n-placeholder="@@evaluation.suite.search"
          label="Filter tasks"
          i18n-label="@@evaluation.suite.filter"
          [query]="f.values().q ?? ''"
          (queryChange)="f.set({ q: $event })"
          [chips]="chips()"
          (chipRemoved)="f.set({ kind: null })"
          (cleared)="f.clear()"
          [presets]="[
            { label: text.prompts, value: 'PROMPT', active: f.values().kind === 'PROMPT' },
            {
              label: text.codingJobs,
              value: 'CODING_JOB',
              active: f.values().kind === 'CODING_JOB',
            },
          ]"
          (presetSelected)="toggleKind($any($event))"
        />

        @if (!s.tasks?.length) {
          <bo-empty
            title="No tasks yet."
            i18n-title="@@evaluation.suite.empty"
            detail="Add prompt tasks (judged by a rubric) or coding jobs (verified by your tests)."
            i18n-detail="@@evaluation.suite.emptyDetail"
            [actionLabel]="canRun() ? text.addTask : null"
            (action)="newTask()"
            help="suites"
            guideLabel="How suites and tasks work"
            i18n-guideLabel="@@evaluation.suite.guide"
          />
        } @else if (!tasks().length) {
          <bo-empty
            title="No task matches these filters."
            i18n-title="@@evaluation.suite.noMatch"
            actionLabel="Clear filters"
            i18n-actionLabel="@@evaluation.clearFilters"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <nz-table [nzData]="tasks()" [nzShowPagination]="false" nzSize="middle">
            <thead>
              <tr>
                <th i18n="@@evaluation.suite.col.task">Task</th>
                <th nzWidth="130px" i18n="@@evaluation.suite.col.category">Category</th>
                <th nzWidth="100px" i18n="@@evaluation.suite.col.difficulty">Difficulty</th>
                <th nzWidth="90px" nzAlign="right" i18n="@@evaluation.suite.col.timeout">
                  Timeout
                </th>
                <th nzWidth="70px" nzAlign="right" i18n="@@evaluation.suite.col.weight">Weight</th>
                <th nzWidth="96px">
                  <span class="sr-only" i18n="@@evaluation.suite.col.actions">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              @for (t of tasks(); track t.id; let i = $index) {
                <tr
                  class="clickable-row"
                  [class.active]="nav.cursor() === i || panel.value() === t.id"
                  [attr.data-nav-index]="i"
                  tabindex="0"
                  (click)="editTask(t)"
                  (keydown.enter)="editTask(t)"
                  [attr.aria-label]="t.name"
                >
                  <td>
                    <div class="cell-main">
                      <div class="cell-line">
                        <span class="cell-title">{{ t.name }}</span>
                        <span class="chip" [class.accent]="t.kind === 'CODING_JOB'">{{
                          t.kind === 'CODING_JOB' ? text.codingJob : text.prompt
                        }}</span>
                      </div>
                      @if (t.kind === 'CODING_JOB' && t.repository?.url) {
                        <span class="key"
                          >{{ t.repository?.url }} @ {{ t.repository?.baseRef }}</span
                        >
                      }
                    </div>
                  </td>
                  <td class="ink-2">{{ category(t.category) }}</td>
                  <td>
                    <span class="chip outline">{{ humanize(t.difficulty).toLowerCase() }}</span>
                  </td>
                  <td nzAlign="right">
                    <span class="mono-cell">{{ timeout(t.timeoutSeconds) }}</span>
                  </td>
                  <td nzAlign="right">
                    <span class="mono-cell">{{ t.weight }}</span>
                  </td>
                  <td (click)="$event.stopPropagation()">
                    <div class="row-actions">
                      @if (canRun()) {
                        <button
                          nz-button
                          nzType="text"
                          nzSize="small"
                          type="button"
                          (click)="duplicateTask(t)"
                          nz-tooltip
                          nzTooltipTitle="Duplicate task"
                          i18n-nzTooltipTitle="@@evaluation.suite.duplicateTask"
                          [attr.aria-label]="duplicateLabel(t)"
                        >
                          <nz-icon nzType="copy" />
                        </button>
                        <button
                          nz-button
                          nzType="text"
                          nzSize="small"
                          class="del"
                          type="button"
                          (click)="deleteTask(t)"
                          nz-tooltip
                          nzTooltipTitle="Delete task"
                          i18n-nzTooltipTitle="@@evaluation.suite.deleteTask"
                          [attr.aria-label]="deleteLabel(t)"
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
      }
    </div>

    <app-task-editor-drawer
      [open]="panelOpen()"
      [task]="editing()"
      [saving]="saving()"
      [readonly]="!canRun()"
      (saved)="saveTask($event)"
      (closed)="closePanel()"
    />

    <nz-modal
      [nzVisible]="editingSuite()"
      nzTitle="Edit suite"
      i18n-nzTitle="@@evaluation.suite.edit"
      nzOkText="Save suite"
      i18n-nzOkText="@@evaluation.suite.save"
      [nzOkLoading]="saving()"
      [nzOkDisabled]="!suiteForm.name.trim()"
      (nzOnOk)="saveSuite()"
      (nzOnCancel)="editingSuite.set(false)"
    >
      <ng-container *nzModalContent>
        <label class="lbl" for="es-name" i18n="@@evaluation.suites.form.name">Name</label>
        <input nz-input id="es-name" [(ngModel)]="suiteForm.name" class="field" />
        <label class="lbl" for="es-desc" i18n="@@evaluation.suites.form.description"
          >Description</label
        >
        <textarea
          nz-input
          id="es-desc"
          [(ngModel)]="suiteForm.description"
          [nzAutosize]="{ minRows: 2, maxRows: 5 }"
          class="field"
        ></textarea>
        <span class="lbl" i18n="@@evaluation.suites.form.weights">Coding score weights</span>
        <div class="weights">
          <label for="es-ver" i18n="@@evaluation.suites.form.verification">Verification</label>
          <nz-input-number
            id="es-ver"
            [(ngModel)]="suiteForm.verification"
            [nzMin]="0"
            [nzMax]="1"
            [nzStep]="0.1"
            style="width: 96px"
          />
          <label for="es-judge" i18n="@@evaluation.suites.form.judge">Judge</label>
          <nz-input-number
            id="es-judge"
            [(ngModel)]="suiteForm.judge"
            [nzMin]="0"
            [nzMax]="1"
            [nzStep]="0.1"
            style="width: 96px"
          />
        </div>
      </ng-container>
    </nz-modal>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .facts {
        display: flex;
        flex-wrap: wrap;
        gap: 12px 40px;
        margin: 0 0 20px;
        padding: 12px 16px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
      }
      .facts dt {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .facts dd {
        margin: 2px 0 0;
        font-size: 14px;
        font-weight: 500;
      }
      .facts dd.num {
        font-family: var(--bo-font-mono);
        font-size: 15px;
      }
      .lbl {
        display: block;
        font-size: 13px;
        font-weight: 500;
        margin-bottom: 4px;
      }
      .field {
        margin-bottom: 12px;
      }
      .weights {
        display: flex;
        gap: 10px;
        align-items: center;
        font-size: 13px;
      }
    `,
  ],
})
export class SuiteDetailPageComponent {
  private readonly api = inject(TaskSuitesApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly breadcrumb = inject(BreadcrumbService);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  readonly identity = inject(IdentityService);
  readonly router = inject(Router);

  readonly suiteId = input.required<string>();
  readonly f = urlFilters({ q: 'string', kind: 'string' });
  readonly panel = panelParam('task');
  readonly suite = signal<TaskSuite | null>(null);
  readonly notFound = signal(false);
  readonly saving = signal(false);
  readonly editingSuite = signal(false);
  /** Source of a duplicate (opened as `?task=new`). */
  private readonly copyOf = signal<EvalTask | null>(null);
  suiteForm = { name: '', description: '', verification: 0.6, judge: 0.4 };
  readonly humanize = humanize;
  readonly category = categoryLabel;
  readonly weights = weightsText;
  readonly timeout = (s: number | null | undefined) => formatDuration((s ?? 0) * 1000);
  readonly text = {
    changedBy: $localize`:@@evaluation.suite.changedBy:changed by`,
    created: $localize`:@@evaluation.suites.created:created`,
    prompts: $localize`:@@evaluation.suite.kind.prompts:Prompts`,
    codingJobs: $localize`:@@evaluation.suite.kind.codingJobs:Coding jobs`,
    prompt: $localize`:@@evaluation.suite.kind.prompt:Prompt`,
    codingJob: $localize`:@@evaluation.suite.kind.codingJob:Coding job`,
    addTask: $localize`:@@evaluation.suite.addTaskAction:Add task`,
  };

  readonly canRun = computed(() => this.identity.can('evaluation.run'));
  readonly stats = computed(() => suiteStats(this.suite()?.tasks ?? []));
  readonly tasks = computed(() =>
    filterTasks(this.suite()?.tasks ?? [], {
      query: this.f.values().q,
      kind: this.f.values().kind,
    }),
  );
  readonly chips = computed<FilterChip[]>(() => {
    const k = this.f.values().kind;
    return k
      ? [{ id: 'kind', label: k === 'CODING_JOB' ? this.text.codingJobs : this.text.prompts }]
      : [];
  });
  readonly editing = computed<EvalTask | null>(() => {
    const id = this.panel.value();
    if (!id) return null;
    if (id === 'new') {
      const src = this.copyOf();
      return src
        ? {
            ...src,
            id: undefined as unknown as string,
            name: $localize`:@@evaluation.suite.copyName:${src.name}:name: (copy)`,
          }
        : null;
    }
    return this.suite()?.tasks?.find((t) => t.id === id) ?? null;
  });
  readonly panelOpen = computed(() => {
    const id = this.panel.value();
    return id === 'new' ? this.canRun() : !!this.editing();
  });
  readonly menu = computed<PageAction[]>(() => {
    const items: PageAction[] = [
      {
        label: $localize`:@@evaluation.suite.reload:Reload suite`,
        icon: 'reload',
        run: () => this.load(),
      },
    ];
    if (this.canRun())
      items.unshift(
        {
          label: $localize`:@@evaluation.suite.editAction:Edit suite`,
          icon: 'edit',
          run: () => this.openEditSuite(),
        },
        {
          label: $localize`:@@evaluation.suite.delete:Delete suite`,
          icon: 'delete',
          danger: true,
          run: () => void this.deleteSuite(),
        },
      );
    return items;
  });

  readonly nav = listNavigation({
    items: this.tasks,
    open: (t) => this.editTask(t),
    group: $localize`:@@evaluation.suite.tasksGroup:Tasks`,
    when: () => !this.panelOpen() && !this.editingSuite(),
  });

  constructor() {
    effect(() => {
      this.suiteId();
      untracked(() => this.load());
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@evaluation.suite.shortcut.search:Search tasks`,
          group: $localize`:@@evaluation.suite.tasksGroup:Tasks`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@evaluation.suite.addTaskAction:Add task`,
          group: $localize`:@@evaluation.suite.tasksGroup:Tasks`,
          when: () => this.canRun() && !this.panelOpen(),
          handler: () => this.newTask(),
        },
      ],
      inject(DestroyRef),
    );
  }

  load(): void {
    this.api.get(this.suiteId()).subscribe({
      next: (s) => {
        this.suite.set(s);
        this.notFound.set(false);
        this.breadcrumb.set([
          { label: $localize`:@@evaluation.title:Evaluation`, link: '/evaluation/suites' },
          { label: $localize`:@@evaluation.nav.suites:Task suites`, link: '/evaluation/suites' },
          { label: s.name },
        ]);
      },
      error: () => this.notFound.set(!this.suite()),
    });
  }

  duplicateLabel(t: EvalTask): string {
    const name = t.name;
    return $localize`:@@evaluation.suite.duplicateLabel:Duplicate ${name}:task:`;
  }

  deleteLabel(t: EvalTask): string {
    const name = t.name;
    return $localize`:@@evaluation.suite.deleteLabel:Delete ${name}:task:`;
  }

  toggleKind(kind: string): void {
    this.f.set({ kind: this.f.values().kind === kind ? null : kind });
  }

  newTask(): void {
    this.copyOf.set(null);
    this.panel.open('new');
  }

  editTask(t: EvalTask): void {
    this.panel.open(t.id);
  }

  duplicateTask(t: EvalTask): void {
    this.copyOf.set(t);
    this.panel.open('new');
  }

  closePanel(): void {
    this.panel.close();
    this.copyOf.set(null);
  }

  saveTask(input: EvalTaskInput): void {
    this.saving.set(true);
    const existing = this.editing();
    const req = existing?.id
      ? this.api.updateTask(this.suiteId(), existing.id, input)
      : this.api.addTask(this.suiteId(), input);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.closePanel();
        this.message.success(
          existing?.id
            ? $localize`:@@evaluation.suite.taskSaved:Task saved`
            : $localize`:@@evaluation.suite.taskAdded:Task added`,
        );
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  async deleteTask(t: EvalTask): Promise<void> {
    const name = t.name;
    const ok = await this.confirm.danger(
      $localize`:@@evaluation.suite.deleteTask.title:Delete task "${name}:task:"?`,
      $localize`:@@evaluation.suite.deleteTask.text:New benchmarks of this suite no longer run it; finished benchmarks keep their attempts and scores.`,
      $localize`:@@evaluation.suite.deleteTaskAction:Delete task`,
    );
    if (!ok) return;
    this.api.deleteTask(this.suiteId(), t.id).subscribe(() => {
      this.message.success($localize`:@@evaluation.suite.taskDeleted:Task deleted`);
      if (this.panel.value() === t.id) this.panel.close();
      this.load();
    });
  }

  openEditSuite(): void {
    const s = this.suite();
    if (!s) return;
    this.suiteForm = {
      name: s.name,
      description: s.description ?? '',
      verification: s.codingScoreWeights?.verification ?? 0.6,
      judge: s.codingScoreWeights?.judge ?? 0.4,
    };
    this.editingSuite.set(true);
  }

  saveSuite(): void {
    if (!this.suiteForm.name.trim()) return;
    this.saving.set(true);
    this.api
      .update(this.suiteId(), {
        name: this.suiteForm.name.trim(),
        description: this.suiteForm.description.trim() || null,
        codingScoreWeights: {
          verification: this.suiteForm.verification,
          judge: this.suiteForm.judge,
        },
      })
      .subscribe({
        next: (s) => {
          this.suite.set({ ...s, tasks: s.tasks ?? this.suite()?.tasks });
          this.saving.set(false);
          this.editingSuite.set(false);
          this.message.success($localize`:@@evaluation.suite.saved:Suite saved`);
        },
        error: () => this.saving.set(false),
      });
  }

  async deleteSuite(): Promise<void> {
    const s = this.suite();
    if (!s) return;
    const name = s.name;
    const ok = await this.confirm.danger(
      $localize`:@@evaluation.suite.deleteSuite.title:Delete suite "${name}:suite:"?`,
      deleteSuiteConsequence(s.tasks?.length ?? 0),
      $localize`:@@evaluation.suite.delete:Delete suite`,
    );
    if (!ok) return;
    this.api.delete(this.suiteId()).subscribe(() => {
      this.message.success($localize`:@@evaluation.suite.deleted:Suite deleted`);

      void this.router.navigate(['/evaluation/suites']);
    });
  }
}

import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzTableModule } from 'ng-zorro-antd/table';
import { TaskSuitesApi } from '../../../../core/api/task-suites.service';
import type { TaskSuiteInput, TaskSuiteSummary } from '../../../../core/api/types';
import { IdentityService } from '../../../../core/auth/identity.service';
import { BreadcrumbService } from '../../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../../core/ui/relay/empty.component';
import { FilterBarComponent } from '../../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../../core/ui/relay/filter-state';
import { listNavigation } from '../../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../../core/ui/relay/table-sort';
import { RELAY_TABLE_STYLES } from '../../../../core/ui/relay/table-styles';
import { EvaluationNavComponent } from '../../components/evaluation-nav.component';
import { filterSuites, weightsText } from '../data/suite-view';

/** Evaluation: task suites. Search lives in the URL; rows link to the suite. */
@Component({
  selector: 'app-suites-page',
  imports: [
    FormsModule,
    RouterLink,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzModalModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    EmptyComponent,
    EvaluationNavComponent,
    FilterBarComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="suites"
        title="Evaluation"
        i18n-title="@@evaluation.title"
        subtitle="Task suites are the work models are measured on: your kind of tickets, specs, reviews and coding jobs."
        i18n-subtitle="@@evaluation.suites.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        @if (canRun()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="openCreate()">
            <nz-icon nzType="plus" />
            <ng-container i18n="@@evaluation.suites.create">Create suite</ng-container>
          </button>
        }
        <app-evaluation-nav boHeaderBelow active="suites" [counts]="{ suites: suites().length }" />
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search suites"
        i18n-placeholder="@@evaluation.suites.search"
        label="Filter task suites"
        i18n-label="@@evaluation.suites.filter"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        (cleared)="f.clear()"
      />

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="4"
          [columns]="4"
          label="Loading task suites"
          i18n-label="@@evaluation.suites.loading"
        />
      } @else if (!filtered().length) {
        @if (f.active()) {
          <bo-empty
            title="No suite matches this search."
            i18n-title="@@evaluation.suites.noMatch"
            actionLabel="Clear search"
            i18n-actionLabel="@@evaluation.suites.clearSearch"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No task suites yet."
            i18n-title="@@evaluation.suites.empty"
            detail="Create one and add tasks that mirror your real work; benchmarks then compare models on it."
            i18n-detail="@@evaluation.suites.emptyDetail"
            [actionLabel]="canRun() ? text.create : null"
            (action)="openCreate()"
            help="suites"
            guideLabel="How task suites work"
            i18n-guideLabel="@@evaluation.suites.guide"
          />
        }
      } @else {
        <nz-table [nzData]="rows()" [nzShowPagination]="false" nzSize="middle">
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@evaluation.suites.col.suite">
                Suite
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="tasks"
                nzWidth="106px"
                nzAlign="right"
                i18n="@@evaluation.suites.col.tasks"
              >
                Tasks
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="weights"
                nzWidth="220px"
                i18n="@@evaluation.suites.col.codingScore"
              >
                Coding score
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="changed"
                nzWidth="220px"
                i18n="@@evaluation.suites.col.changed"
              >
                Changed
              </th>
            </tr>
          </thead>
          <tbody>
            @for (s of rows(); track s.id; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i"
                [attr.data-nav-index]="i"
                tabindex="-1"
                (click)="open(s)"
              >
                <td>
                  <div class="cell-main">
                    <a
                      class="cell-title"
                      [routerLink]="['/evaluation/suites', s.id]"
                      (click)="$event.stopPropagation()"
                      >{{ s.name }}</a
                    >
                    @if (s.description) {
                      <span class="cell-sub wide" [attr.title]="s.description">{{
                        s.description
                      }}</span>
                    }
                  </div>
                </td>
                <td nzAlign="right">
                  <span class="mono-cell">{{ s.taskCount ?? 0 }}</span>
                </td>
                <td>
                  <span class="ink-2 small">{{ weights(s) }}</span>
                </td>
                <td>
                  <bo-provenance
                    [verb]="s.updatedBy ? text.by : text.created"
                    [actor]="s.updatedBy"
                    [at]="s.createdAt"
                  />
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>

    <nz-modal
      [nzVisible]="creating()"
      nzTitle="Create task suite"
      i18n-nzTitle="@@evaluation.suites.form.title"
      nzOkText="Create suite"
      i18n-nzOkText="@@evaluation.suites.create"
      [nzOkLoading]="saving()"
      [nzOkDisabled]="!form.name.trim()"
      (nzOnOk)="create()"
      (nzOnCancel)="creating.set(false)"
    >
      <ng-container *nzModalContent>
        <form nz-form nzLayout="vertical" (submit)="$event.preventDefault(); create()">
          <nz-form-item>
            <nz-form-label nzRequired nzFor="s-name" i18n="@@evaluation.suites.form.name"
              >Name</nz-form-label
            >
            <nz-form-control
              ><input
                nz-input
                id="s-name"
                name="name"
                [(ngModel)]="form.name"
                placeholder="Backend SDLC tasks"
                i18n-placeholder="@@evaluation.suites.form.namePlaceholder"
            /></nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="s-desc" i18n="@@evaluation.suites.form.description"
              >Description</nz-form-label
            >
            <nz-form-control>
              <textarea
                nz-input
                id="s-desc"
                name="description"
                [(ngModel)]="form.description"
                [nzAutosize]="{ minRows: 2, maxRows: 5 }"
              ></textarea>
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label i18n="@@evaluation.suites.form.weights"
              >Coding score weights</nz-form-label
            >
            <nz-form-control
              nzExtra="Coding jobs score = verification × passed (0 or 100) + judge × judge score. The two should sum to 1."
              i18n-nzExtra="@@evaluation.suites.form.weightsHint"
            >
              <div class="weights">
                <label for="s-ver" i18n="@@evaluation.suites.form.verification">Verification</label>
                <nz-input-number
                  id="s-ver"
                  name="verification"
                  [(ngModel)]="form.verification"
                  [nzMin]="0"
                  [nzMax]="1"
                  [nzStep]="0.1"
                  style="width: 96px"
                />
                <label for="s-judge" i18n="@@evaluation.suites.form.judge">Judge</label>
                <nz-input-number
                  id="s-judge"
                  name="judge"
                  [(ngModel)]="form.judge"
                  [nzMin]="0"
                  [nzMax]="1"
                  [nzStep]="0.1"
                  style="width: 96px"
                />
              </div>
            </nz-form-control>
          </nz-form-item>
        </form>
      </ng-container>
    </nz-modal>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .wide {
        max-width: 110ch;
      }
      .small {
        font-size: 12px;
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
export class SuitesPageComponent {
  private readonly api = inject(TaskSuitesApi);
  private readonly router = inject(Router);
  private readonly message = inject(NzMessageService);
  private readonly identity = inject(IdentityService);
  private readonly bar = viewChild<FilterBarComponent>('bar');

  readonly f = urlFilters({ q: 'string' });
  readonly suites = signal<TaskSuiteSummary[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly creating = signal(false);
  readonly saving = signal(false);
  readonly canRun = computed(() => this.identity.can('evaluation.run'));
  readonly filtered = computed(() => filterSuites(this.suites(), this.f.values().q));
  readonly weights = weightsText;
  /** Column sorting (URL `?sort=`); the same order for the table and keyboard navigation. */
  readonly sort = tableSort<TaskSuiteSummary>({
    name: (s) => s.name,
    tasks: (s) => s.taskCount ?? 0,
    weights: (s) => weightsText(s),
    changed: (s) => s.createdAt,
  });
  readonly rows = computed(() => this.sort.apply(this.filtered()));
  form = { name: '', description: '', verification: 0.6, judge: 0.4 };
  readonly text = {
    reload: $localize`:@@evaluation.suites.reload:Reload suites`,
    create: $localize`:@@evaluation.suites.createAction:Create suite`,
    by: $localize`:@@evaluation.suites.by:by`,
    created: $localize`:@@evaluation.suites.created:created`,
  };

  readonly nav = listNavigation({
    items: this.rows,
    open: (s) => this.open(s),
    group: $localize`:@@evaluation.nav.suites:Task suites`,
    when: () => !this.creating(),
  });
  readonly reload = () => this.load();

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@evaluation.title:Evaluation`, link: '/evaluation/suites' },
      { label: $localize`:@@evaluation.nav.suites:Task suites` },
    ]);
    this.load();
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@evaluation.suites.shortcut.search:Search suites`,
          group: $localize`:@@evaluation.nav.suites:Task suites`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@evaluation.suites.createAction:Create suite`,
          group: $localize`:@@evaluation.nav.suites:Task suites`,
          when: () => this.canRun() && !this.creating(),
          handler: () => this.openCreate(),
        },
      ],
      inject(DestroyRef),
    );
  }

  load(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (list) => {
        this.suites.set(list);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  open(s: TaskSuiteSummary): void {
    void this.router.navigate(['/evaluation/suites', s.id]);
  }

  openCreate(): void {
    this.form = { name: '', description: '', verification: 0.6, judge: 0.4 };
    this.creating.set(true);
  }

  create(): void {
    if (!this.form.name.trim()) return;
    const input: TaskSuiteInput = {
      name: this.form.name.trim(),
      description: this.form.description.trim() || null,
      codingScoreWeights: { verification: this.form.verification, judge: this.form.judge },
    };
    this.saving.set(true);
    this.api.create(input).subscribe({
      next: (s) => {
        this.saving.set(false);
        this.creating.set(false);
        this.message.success($localize`:@@evaluation.suites.created.toast:Suite created`);

        void this.router.navigate(['/evaluation/suites', s.id]);
      },
      error: () => this.saving.set(false),
    });
  }
}

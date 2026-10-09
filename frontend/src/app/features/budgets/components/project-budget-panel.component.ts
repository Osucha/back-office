import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { BudgetsApi } from '../../../core/api/budgets.service';
import type { BudgetInput, BudgetStatus } from '../../../core/api/types';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { BudgetEditorComponent } from './budget-editor.component';

/** Project settings tab "Budget" (`GET/PUT /projects/{id}/budget`); read-only without `project.manage`. */
@Component({
  selector: 'app-project-budget-panel',
  imports: [BudgetEditorComponent, EmptyComponent, SkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loading() && !status()) {
      <bo-skeleton
        variant="cards"
        [rows]="1"
        label="Loading the budget"
        i18n-label="@@budgets.project.loading"
      />
    } @else if (!status()) {
      <bo-empty
        title="The project budget is not available."
        i18n-title="@@budgets.project.unavailable.title"
        [detail]="text.unavailableDetail"
        actionLabel="Try again"
        i18n-actionLabel="@@budgets.project.unavailable.retry"
        (action)="load()"
      />
    } @else {
      <section class="card" aria-labelledby="h-budget">
        <h2 id="h-budget">
          @if (canManage()) {
            <ng-container i18n="@@budgets.project.title">Monthly budget</ng-container>
          } @else {
            <ng-container i18n="@@budgets.project.titleReadOnly"
              >Monthly budget (read-only)</ng-container
            >
          }
        </h2>
        <p class="lede" i18n="@@budgets.project.lede">
          Every LLM call of the project's agents is checked against it at the gateway, before and
          after the call. The platform budget applies on top.
        </p>
        <app-budget-editor
          [status]="status()"
          [readonly]="!canManage()"
          [saveFn]="save"
          saveLabel="Save project budget"
          i18n-saveLabel="@@budgets.project.save"
          (saved)="status.set($event)"
        />
      </section>
    }
  `,
  styles: [
    `
      .card {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 16px 20px 20px;
        max-width: 1080px;
      }
      h2 {
        margin: 0 0 4px;
        font-size: 16px;
        font-weight: 600;
      }
      .lede {
        margin: 0 0 16px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        max-width: 80ch;
      }
    `,
  ],
})
export class ProjectBudgetPanelComponent {
  private readonly api = inject(BudgetsApi);
  readonly projectId = input.required<string>();
  readonly canManage = input(false);
  readonly status = signal<BudgetStatus | null>(null);
  readonly loading = signal(false);

  readonly save = (b: BudgetInput) => this.api.setProject(this.projectId(), b);
  readonly text = {
    unavailableDetail: $localize`:@@budgets.project.unavailable.detail:This backend does not expose ${'/projects/{id}/budget'}:endpoint: yet.`,
  };

  constructor() {
    effect(() => {
      this.projectId();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.loading.set(true);
    this.api.project(this.projectId()).subscribe({
      next: (s) => {
        this.status.set(s);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}

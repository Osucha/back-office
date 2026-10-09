import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { BudgetsApi } from '../../../core/api/budgets.service';
import type { BudgetStatus } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { budgetBaton, spendText } from '../../../core/state/budget-state';
import { BatonComponent } from '../../../core/ui/relay/baton.component';

/**
 * Compact budget state for page headers (Costs): the platform budget, or the selected project's,
 * as a baton with "spent of limit" and a link to change it for people who may. Renders nothing
 * when the backend has no budgets.
 */
@Component({
  selector: 'app-budget-summary',
  imports: [RouterLink, BatonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (shown(); as b) {
      <span class="summary">
        <span class="scope">{{ scopeLabel() }}</span>
        <bo-baton size="sm" [baton]="baton()" />
        <span class="spend">{{ spend() }}</span>
        @if (link(); as l) {
          <a [routerLink]="l.path" [queryParams]="l.query">
            @if (canManage()) {
              <ng-container i18n="@@budgets.summary.change">Change</ng-container>
            } @else {
              <ng-container i18n="@@budgets.summary.view">View</ng-container>
            }
          </a>
        }
      </span>
    }
  `,
  styles: [
    `
      .summary {
        display: inline-flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .scope {
        color: var(--bo-ink-3);
      }
      .spend {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
    `,
  ],
})
export class BudgetSummaryComponent {
  private readonly identity = inject(IdentityService);
  /** The project filter of the page; null = the platform budget (or the only one visible). */
  readonly projectId = input<string | null>(null);
  readonly budgets = signal<BudgetStatus[]>([]);

  readonly shown = computed(() => {
    const list = this.budgets();
    const id = this.projectId();
    if (id) return list.find((b) => b.scope === 'PROJECT' && b.projectId === id) ?? null;
    return list.find((b) => b.scope === 'PLATFORM') ?? (list.length === 1 ? list[0] : null);
  });
  readonly canManage = computed(() => {
    const b = this.shown();
    if (!b) return false;
    return b.scope === 'PLATFORM'
      ? this.identity.can('admin.settings.manage')
      : !!b.projectId && this.identity.canInProject('project.manage', b.projectId);
  });
  readonly baton = computed(() => budgetBaton(this.shown(), this.canManage()));
  readonly spend = computed(() => spendText(this.shown()));
  readonly scopeLabel = computed(() => {
    const b = this.shown();
    if (b?.scope === 'PLATFORM') return $localize`:@@budgets.summary.platform:Platform budget`;
    return b?.projectKey
      ? $localize`:@@budgets.summary.project:${b.projectKey}:key: budget`
      : $localize`:@@budgets.summary.projectNoKey:Project budget`;
  });
  readonly link = computed(() => {
    const b = this.shown();
    if (!b) return null;
    if (b.scope === 'PLATFORM')
      return this.identity.can('admin.settings.manage')
        ? { path: ['/admin/settings'], query: { section: 'budget' } }
        : null;
    return { path: ['/projects', b.projectId], query: { tab: 'budget' } };
  });

  constructor() {
    inject(BudgetsApi)
      .list()
      .pipe(catchError(() => of([] as BudgetStatus[])))
      .subscribe((l) => this.budgets.set(Array.isArray(l) ? l : []));
  }
}

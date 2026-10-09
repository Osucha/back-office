import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  inject,
  input,
  signal,
} from '@angular/core';
import { BudgetsApi } from '../../../core/api/budgets.service';
import type { BudgetInput, BudgetStatus } from '../../../core/api/types';
import { BudgetEditorComponent } from './budget-editor.component';

/**
 * Platform budget on the platform settings page (`GET /budgets` → PLATFORM, `PUT /admin/budget`).
 * `scrollIntoView` (from `?section=budget`) brings it into view after the first render.
 */
@Component({
  selector: 'app-platform-budget-panel',
  imports: [BudgetEditorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section id="budget" class="card" aria-labelledby="h-platform-budget">
      <h2 id="h-platform-budget" i18n="@@budgets.platform.title">Platform budget</h2>
      <p class="lede" i18n="@@budgets.platform.lede">
        A monthly ceiling over every project. Project budgets apply inside it; the stricter one
        stops agents first.
      </p>
      @if (loaded() && !available()) {
        <p class="lede" i18n="@@budgets.platform.unavailable">
          This backend does not expose budgets yet (<code>GET /budgets</code>).
        </p>
      } @else {
        <app-budget-editor
          [status]="status()"
          [saveFn]="save"
          saveLabel="Save platform budget"
          i18n-saveLabel="@@budgets.platform.save"
          (saved)="status.set($event)"
        />
      }
    </section>
  `,
  styles: [
    `
      .card {
        max-width: 1040px;
        margin-top: 24px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 16px 20px 20px;
        scroll-margin-top: calc(var(--bo-topbar-h) + 16px);
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
      }
      code {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
    `,
  ],
})
export class PlatformBudgetPanelComponent {
  private readonly api = inject(BudgetsApi);
  readonly scrollIntoView = input(false);
  readonly status = signal<BudgetStatus | null>(null);
  readonly loaded = signal(false);
  readonly available = signal(false);
  readonly save = (b: BudgetInput) => this.api.setPlatform(b);

  constructor() {
    const host = inject(ElementRef<HTMLElement>);
    this.api.list().subscribe({
      next: (list) => {
        const p = (Array.isArray(list) ? list : []).find((b) => b.scope === 'PLATFORM');
        this.status.set(
          p ?? {
            scope: 'PLATFORM',
            state: 'NO_LIMIT',
            spentUsd: 0,
            alertAtPercent: 80,
            hardStop: true,
          },
        );
        this.available.set(true);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
    afterNextRender(() => {
      if (this.scrollIntoView())
        setTimeout(
          () => (host.nativeElement as HTMLElement).scrollIntoView({ block: 'start' }),
          300,
        );
    });
  }
}

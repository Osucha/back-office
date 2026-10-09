import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { Observable } from 'rxjs';
import { problemOf } from '../../../core/forms/server-errors';
import type { BudgetInput, BudgetStatus } from '../../../core/api/types';
import {
  budgetBaton,
  budgetInputErrors,
  budgetShare,
  forecastView,
  formatShare,
  monthName,
  spendText,
} from '../../../core/state/budget-state';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { formatCost } from '../../../core/ui/relay/format';

let nextId = 0;

/**
 * Monthly budget: current spend against the limit (bar with the alert threshold and the month-end
 * forecast marker), state baton, and the form (limit on/off, amount, alert %, hard stop). The
 * caller passes the status and a `save(input)` function (project or platform endpoint).
 */
@Component({
  selector: 'app-budget-editor',
  imports: [FormsModule, NzButtonModule, NzInputNumberModule, NzSwitchModule, BatonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let b = status();
    <div class="budget">
      <div class="now">
        <div class="head">
          <div>
            <div class="eyebrow" i18n="@@budgets.editor.spentIn">Spent in {{ month() }}</div>
            <div class="figure">{{ spent() }}</div>
            <div class="of">{{ spendLine() }}</div>
          </div>
          <bo-baton [baton]="baton()" />
        </div>
        @if (share() !== null) {
          <div class="bar" role="img" [attr.aria-label]="barLabel()">
            <span
              class="fill"
              [attr.data-tone]="baton().holder"
              [style.width.%]="min(100, share()! * 100)"
            ></span>
            <span
              class="alert-mark"
              [style.left.%]="alertAt()"
              title="Alert threshold"
              i18n-title="@@budgets.editor.alertThreshold"
            ></span>
            @if (forecast().share !== null) {
              <span
                class="forecast-mark"
                [style.left.%]="min(100, forecast().share! * 100)"
                title="Forecast"
                i18n-title="@@budgets.editor.forecastMark"
              ></span>
            }
          </div>
          <div class="legend">
            <span i18n="@@budgets.editor.used">{{ pct() }} used</span>
            <span i18n="@@budgets.editor.alertAt">alert at {{ alertAt() }}%</span>
          </div>
        }
        <p class="forecast" [attr.data-tone]="forecast().tone">
          <strong i18n="@@budgets.editor.forecast">Forecast:</strong> {{ forecast().text }}
          @if (forecast().verdict) {
            · {{ forecast().verdict }}
          }
        </p>
        @if (b?.state === 'EXCEEDED' && b?.hardStop) {
          <p class="stop" i18n="@@budgets.editor.stopped">
            Agents are stopped: the LLM gateway refuses calls until the limit is raised or the month
            ends.
          </p>
        }
      </div>

      <form class="form" (submit)="$event.preventDefault(); submit()">
        <div class="row">
          <label class="lbl" [for]="id + '-on'" i18n="@@budgets.editor.monthlyLimit"
            >Monthly limit</label
          >
          <nz-switch
            [nzId]="id + '-on'"
            name="limited"
            [ngModel]="limited()"
            (ngModelChange)="setLimited($event)"
            [nzDisabled]="readonly()"
          />
          <span class="help">{{ limitHelp() }}</span>
        </div>
        @if (limited()) {
          <div class="row">
            <label class="lbl" [for]="id + '-limit'" i18n="@@budgets.editor.limit"
              >Limit (USD)</label
            >
            <nz-input-number
              [nzId]="id + '-limit'"
              name="limit"
              [nzMin]="1"
              [nzStep]="50"
              [nzPrecision]="2"
              [ngModel]="limit()"
              (ngModelChange)="limit.set($event)"
              [nzDisabled]="readonly()"
              style="width: 160px"
            />
            @if (errors().monthlyLimitUsd) {
              <span class="err">{{ errors().monthlyLimitUsd }}</span>
            }
          </div>
          <div class="row">
            <label class="lbl" [for]="id + '-alert'" i18n="@@budgets.editor.alertAtLabel"
              >Alert at (%)</label
            >
            <nz-input-number
              [nzId]="id + '-alert'"
              name="alert"
              [nzMin]="1"
              [nzMax]="100"
              [nzStep]="5"
              [ngModel]="alert()"
              (ngModelChange)="alert.set($event)"
              [nzDisabled]="readonly()"
              style="width: 100px"
            />
            <span class="help">{{ errors().alertAtPercent || text.alertHelp }}</span>
          </div>
          <div class="row">
            <label class="lbl" [for]="id + '-stop'" i18n="@@budgets.editor.hardStop"
              >Hard stop</label
            >
            <nz-switch
              [nzId]="id + '-stop'"
              name="hardStop"
              [ngModel]="hardStop()"
              (ngModelChange)="hardStop.set($event)"
              [nzDisabled]="readonly()"
            />
            <span class="help">{{ hardStopHelp() }}</span>
          </div>
        }
        @if (!readonly()) {
          <div class="actions">
            <button
              nz-button
              type="button"
              [disabled]="!dirty()"
              (click)="reset()"
              i18n="@@budgets.editor.discard"
            >
              Discard
            </button>
            <button
              nz-button
              nzType="primary"
              type="submit"
              [disabled]="!dirty() || !valid()"
              [nzLoading]="saving()"
            >
              {{ saveLabel() }}
            </button>
          </div>
        }
      </form>
    </div>
  `,
  styles: [
    `
      .budget {
        display: grid;
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
        gap: 24px;
      }
      @media (max-width: 900px) {
        .budget {
          grid-template-columns: 1fr;
        }
      }
      .head {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: 12px;
      }
      .eyebrow {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .figure {
        font-family: var(--bo-font-mono);
        font-size: 32px;
        font-weight: 600;
        letter-spacing: -0.02em;
        font-variant-numeric: tabular-nums;
        line-height: 1.15;
      }
      .of {
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .bar {
        position: relative;
        height: 10px;
        margin: 14px 0 4px;
        border-radius: 999px;
        background: var(--bo-surface-3);
      }
      .fill {
        position: absolute;
        inset: 0 auto 0 0;
        border-radius: 999px;
        background: var(--bo-done);
      }
      .fill[data-tone='you'] {
        background: var(--bo-you);
      }
      .fill[data-tone='blocked'] {
        background: var(--bo-blocked);
      }
      .fill[data-tone='idle'] {
        background: var(--bo-idle);
      }
      .alert-mark,
      .forecast-mark {
        position: absolute;
        top: -4px;
        bottom: -4px;
        width: 2px;
        margin-left: -1px;
        background: var(--bo-ink-2);
      }
      .forecast-mark {
        background: transparent;
        border-left: 2px dashed var(--bo-ink);
      }
      .legend {
        display: flex;
        justify-content: space-between;
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .forecast {
        margin: 12px 0 0;
        font-size: 13px;
      }
      .forecast[data-tone='you'] {
        color: var(--bo-you-ink);
      }
      .forecast[data-tone='blocked'] {
        color: var(--bo-blocked-ink);
      }
      .stop {
        margin: 8px 0 0;
        padding: 8px 10px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
        font-size: 12.5px;
      }
      .form {
        display: grid;
        gap: 12px;
        align-content: start;
      }
      .row {
        display: grid;
        grid-template-columns: 120px auto minmax(0, 1fr);
        gap: 12px;
        align-items: center;
      }
      .lbl {
        font-size: 13px;
        font-weight: 500;
      }
      .help {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .err {
        font-size: 12px;
        color: var(--bo-blocked-ink);
      }
      .actions {
        display: flex;
        justify-content: flex-end;
        gap: 8px;
        padding-top: 8px;
        border-top: 1px solid var(--bo-line);
      }
    `,
  ],
})
export class BudgetEditorComponent {
  private readonly notify = inject(NotifyService);
  private readonly message = inject(NzMessageService);
  private readonly pendingSaves = inject(PendingSavesService);

  readonly status = input<BudgetStatus | null>(null);
  readonly readonly = input(false);
  readonly saveLabel = input($localize`:@@budgets.editor.save:Save budget`);
  /** Sends the input; the component tracks the save and reports errors. */
  readonly saveFn = input<((b: BudgetInput) => Observable<BudgetStatus>) | null>(null);
  readonly saved = output<BudgetStatus>();

  readonly id = `bo-budget-${nextId++}`;
  readonly limited = signal(false);
  readonly limit = signal<number | null>(null);
  readonly alert = signal<number | null>(80);
  readonly hardStop = signal(true);
  readonly saving = signal(false);

  readonly baton = computed(() => budgetBaton(this.status(), !this.readonly()));
  readonly month = computed(() => monthName(this.status()?.period, true));
  readonly spent = computed(() => formatCost(this.status()?.spentUsd ?? 0));
  readonly spendLine = computed(() => {
    const b = this.status();
    return b?.monthlyLimitUsd
      ? $localize`:@@budgets.editor.ofLimit:of ${formatCost(b.monthlyLimitUsd)}:limit: limit`
      : spendText(b);
  });
  readonly text = {
    alertHelp: $localize`:@@budgets.editor.alertHelp:Everyone who can manage the project gets a turn card and a BudgetAlert event.`,
  };
  readonly limitHelp = computed(() =>
    this.limited()
      ? $localize`:@@budgets.editor.limitedHelp:Spending is capped per calendar month (UTC).`
      : $localize`:@@budgets.editor.unlimitedHelp:No limit: spend is only reported.`,
  );
  readonly hardStopHelp = computed(() =>
    this.hardStop()
      ? $localize`:@@budgets.editor.hardStopOnHelp:At the limit, agents get HTTP 402 and their jobs fail with budget-exceeded.`
      : $localize`:@@budgets.editor.hardStopOffHelp:At the limit, work continues and the overrun is reported.`,
  );
  readonly share = computed(() =>
    budgetShare(this.status()?.spentUsd, this.status()?.monthlyLimitUsd),
  );
  readonly pct = computed(() => formatShare(this.share()));
  readonly alertAt = computed(() => this.status()?.alertAtPercent ?? 80);
  readonly forecast = computed(() => forecastView(this.status()));
  readonly barLabel = computed(
    () =>
      $localize`:@@budgets.editor.barLabel:${this.pct()}:used: of the monthly limit used; alert at ${this.alertAt()}:alert:%; ${this.forecast().text}:forecast:`,
  );
  private readonly input = computed<BudgetInput>(() => ({
    monthlyLimitUsd: this.limited() ? this.limit() : null,
    alertAtPercent: this.alert() ?? 80,
    hardStop: this.hardStop(),
  }));
  readonly errors = computed(() =>
    budgetInputErrors({
      monthlyLimitUsd: this.input().monthlyLimitUsd ?? null,
      alertAtPercent: this.alert(),
    }),
  );
  readonly valid = computed(() => !Object.keys(this.errors()).length);
  readonly dirty = computed(() => {
    const b = this.status();
    const i = this.input();
    return (
      (i.monthlyLimitUsd ?? null) !== (b?.monthlyLimitUsd ?? null) ||
      i.alertAtPercent !== (b?.alertAtPercent ?? 80) ||
      i.hardStop !== (b?.hardStop ?? true)
    );
  });
  min = Math.min;

  constructor() {
    effect(() => {
      this.status();
      untracked(() => this.reset());
    });
  }

  setLimited(on: boolean): void {
    this.limited.set(on);
    if (on && !this.limit()) this.limit.set(this.status()?.monthlyLimitUsd ?? 100);
  }

  reset(): void {
    const b = this.status();
    this.limited.set(b?.monthlyLimitUsd !== null && b?.monthlyLimitUsd !== undefined);
    this.limit.set(b?.monthlyLimitUsd ?? null);
    this.alert.set(b?.alertAtPercent ?? 80);
    this.hardStop.set(b?.hardStop ?? true);
  }

  submit(): void {
    const fn = this.saveFn();
    if (!fn || !this.valid() || this.readonly()) return;
    this.saving.set(true);
    this.pendingSaves.track(fn(this.input())).subscribe({
      next: (s) => {
        this.saving.set(false);
        this.message.success($localize`:@@budgets.editor.saved:Budget saved`);
        this.saved.emit(s);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        const p = problemOf(err);
        const fields = (p?.errors ?? []).map((e) => `${e.field}: ${e.message}`).join('; ');
        this.notify.error(
          $localize`:@@budgets.editor.saveFailed:Could not save the budget`,
          [p?.detail || p?.title, fields].filter(Boolean).join(' · '),
        );
      },
    });
  }
}

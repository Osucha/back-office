import {
  ChangeDetectionStrategy,
  Component,
  type OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzMessageService } from 'ng-zorro-antd/message';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import type { ModelDefaults, ModelOption, StepKind } from '../../../core/api/types';
import { ModelSelectComponent } from '../../../core/ui/model-select.component';
import {
  type DefaultModelRow,
  type ProfileScope,
  defaultModelRows,
  defaultProfile,
} from '../data/model-scope';

type Cell = `${ProfileScope}:${StepKind}`;

/**
 * Default models per kind of step (ADR 0050 step 2): the platform default (models.manage) and the user's own, used
 * by steps whose model is set neither on the step, the template nor the project.
 */
@Component({
  selector: 'app-default-models',
  imports: [FormsModule, ModelSelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="defaults" aria-labelledby="defaults-title">
      <div class="head">
        <h2 id="defaults-title" i18n="@@models.defaults.title">Default models</h2>
        <p class="sub" i18n="@@models.defaults.order">
          A step takes its model from the step itself, then its template, the project default, your
          default and finally the platform default.
        </p>
      </div>
      <div class="grid" role="table" aria-labelledby="defaults-title">
        <div class="row header" role="row">
          <span role="columnheader" i18n="@@models.defaults.kind">Kind of step</span>
          <span role="columnheader" i18n="@@models.defaults.platform">Platform default</span>
          <span role="columnheader" i18n="@@models.defaults.mine">My default</span>
        </div>
        @for (r of rows; track r.stepKind) {
          <div class="row" role="row">
            <span class="kind" role="rowheader">{{ r.label }}</span>
            <div role="cell">
              @if (canManage()) {
                <label class="sr-only" [for]="'def-platform-' + r.stepKind">{{
                  platformLabel(r)
                }}</label>
                <app-model-select
                  [selectId]="'def-platform-' + r.stepKind"
                  [options]="options()['PLATFORM:' + r.stepKind] ?? []"
                  [loading]="loading()"
                  [placeholder]="text.notSet"
                  [ngModel]="value('PLATFORM', r)"
                  (ngModelChange)="change('PLATFORM', r, $event)"
                  [disabled]="busy() === 'PLATFORM:' + r.stepKind"
                />
              } @else {
                <span class="fixed" [class.quiet]="!platformName(r)">{{
                  platformName(r) || text.notSet
                }}</span>
              }
            </div>
            <div role="cell">
              <label class="sr-only" [for]="'def-mine-' + r.stepKind">{{ mineLabel(r) }}</label>
              <app-model-select
                [selectId]="'def-mine-' + r.stepKind"
                [options]="options()['PERSONAL:' + r.stepKind] ?? []"
                [loading]="loading()"
                [placeholder]="text.usePlatform"
                [ngModel]="value('PERSONAL', r)"
                (ngModelChange)="change('PERSONAL', r, $event)"
                [disabled]="busy() === 'PERSONAL:' + r.stepKind"
              />
            </div>
          </div>
        }
      </div>
    </section>
  `,
  styles: [
    `
      .defaults {
        margin: 0 0 20px;
        padding: 12px 14px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
      }
      .head {
        margin-bottom: 10px;
      }
      h2 {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
        color: var(--bo-ink);
      }
      .sub {
        margin: 2px 0 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .row {
        display: grid;
        grid-template-columns: 140px minmax(0, 1fr) minmax(0, 1fr);
        gap: 12px;
        align-items: start;
        padding: 6px 0;
      }
      .row.header {
        font-size: 12px;
        color: var(--bo-ink-3);
        padding-top: 0;
      }
      .kind {
        font-size: 13px;
        color: var(--bo-ink);
        line-height: 32px;
      }
      .fixed {
        display: block;
        font-size: 13px;
        line-height: 32px;
      }
      .quiet {
        color: var(--bo-ink-3);
      }
      @media (max-width: 720px) {
        .row {
          grid-template-columns: minmax(0, 1fr);
          gap: 4px;
        }
        .row.header {
          display: none;
        }
      }
    `,
  ],
})
export class DefaultModelsComponent implements OnInit {
  private readonly api = inject(ModelProfilesApi);
  private readonly message = inject(NzMessageService);

  /** Whether the platform defaults can be changed (models.manage). */
  readonly canManage = input(false);

  readonly rows: DefaultModelRow[] = defaultModelRows();
  readonly defaults = signal<ModelDefaults | null>(null);
  /** The selected profile id per cell; set at once on a change and put back when the change fails. */
  readonly values = signal<Partial<Record<Cell, string | null>>>({});
  readonly options = signal<Partial<Record<string, ModelOption[]>>>({});
  readonly loading = signal(false);
  readonly busy = signal<Cell | null>(null);
  readonly text = {
    notSet: $localize`:@@models.defaults.notSet:Not set`,
    usePlatform: $localize`:@@models.defaults.usePlatform:Not set: the platform default applies`,
    saved: $localize`:@@models.defaults.saved:Default model saved`,
    cleared: $localize`:@@models.defaults.cleared:Default model cleared`,
  };

  ngOnInit(): void {
    this.load();
  }

  value(scope: ProfileScope, r: DefaultModelRow): string | null {
    return this.values()[`${scope}:${r.stepKind}`] ?? null;
  }

  platformName(r: DefaultModelRow): string | null {
    return defaultProfile(this.defaults(), 'PLATFORM', r.stepKind)?.displayName ?? null;
  }

  platformLabel(r: DefaultModelRow): string {
    const kind = r.label;
    return $localize`:@@models.defaults.platformLabel:Platform default for ${kind}:kind:`;
  }

  mineLabel(r: DefaultModelRow): string {
    const kind = r.label;
    return $localize`:@@models.defaults.mineLabel:My default for ${kind}:kind:`;
  }

  /** Reloads the defaults and the options (after a profile was created, changed or deleted). */
  load(): void {
    this.api.defaults().subscribe({ next: (d) => this.apply(d), error: () => undefined });
    this.loading.set(true);
    let pending = 0;
    const done = () => {
      if (--pending === 0) this.loading.set(false);
    };
    const scopes: ProfileScope[] = this.canManage() ? ['PLATFORM', 'PERSONAL'] : ['PERSONAL'];
    for (const r of this.rows)
      for (const scope of scopes) {
        pending++;
        this.api.defaultOptions(r.stepKind, scope, r.harness).subscribe({
          next: (list) => {
            this.options.update((o) => ({ ...o, [`${scope}:${r.stepKind}`]: list }));
            done();
          },
          error: done,
        });
      }
  }

  change(scope: ProfileScope, r: DefaultModelRow, profileId: string | null): void {
    const cell: Cell = `${scope}:${r.stepKind}`;
    if ((this.values()[cell] ?? null) === profileId) return;
    const previous = this.values()[cell] ?? null;
    this.values.update((v) => ({ ...v, [cell]: profileId }));
    this.busy.set(cell);
    const req =
      scope === 'PLATFORM'
        ? this.api.setPlatformDefault(r.stepKind, profileId)
        : this.api.setMyDefault(r.stepKind, profileId);
    req.subscribe({
      next: (d) => {
        this.busy.set(null);
        this.apply(d);
        this.message.success(profileId ? this.text.saved : this.text.cleared);
      },
      error: () => {
        this.busy.set(null);
        this.values.update((v) => ({ ...v, [cell]: previous }));
      },
    });
  }

  private apply(d: ModelDefaults): void {
    this.defaults.set(d);
    const values: Partial<Record<Cell, string | null>> = {};
    for (const r of this.rows)
      for (const scope of ['PLATFORM', 'PERSONAL'] as const)
        values[`${scope}:${r.stepKind}`] = defaultProfile(d, scope, r.stepKind)?.id ?? null;
    this.values.set(values);
  }
}

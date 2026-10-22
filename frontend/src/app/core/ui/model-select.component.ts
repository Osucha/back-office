import {
  ChangeDetectionStrategy,
  Component,
  computed,
  forwardRef,
  input,
  signal,
} from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { type ModelChoice, groupModelOptions, routeLabel } from './model-options';

/**
 * Model profile picker (ADR 0050): the options a step may use, grouped Platform / Personal, each with its route
 * tag; unusable options are disabled with their reason. The chosen model's problem (error), note (warning) or
 * hint shows under the select. A form control (ngModel / formControlName) holding a profile id or null.
 */
@Component({
  selector: 'app-model-select',
  imports: [FormsModule, NzSelectModule, NzTagModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => ModelSelectComponent),
      multi: true,
    },
  ],
  template: `
    <nz-select
      [nzId]="selectId()"
      nzShowSearch
      [nzAllowClear]="allowClear()"
      [nzPlaceHolder]="placeholder()"
      [nzLoading]="loading()"
      [nzDisabled]="disabled()"
      [ngModel]="value()"
      (ngModelChange)="select($event)"
      (nzBlur)="touched()"
    >
      @for (g of groups(); track g.key) {
        <nz-option-group [nzLabel]="g.label">
          @for (o of g.options; track o.profileId) {
            <nz-option
              [nzValue]="o.profileId"
              [nzLabel]="o.displayName + ' (' + o.modelId + ')'"
              [nzDisabled]="!o.usable"
              nzCustomContent
            >
              <div class="opt" [title]="o.problem ?? ''">
                <span class="name">{{ o.displayName }}</span>
                <span class="mono muted">{{ o.modelId }}</span>
                <nz-tag class="route" [nzColor]="o.route === 'OWN_LOGIN' ? 'purple' : 'default'">{{
                  route(o)
                }}</nz-tag>
              </div>
              @if (!o.usable && o.problem) {
                <div class="desc danger">{{ o.problem }}</div>
              } @else if (o.hint) {
                <div class="desc muted">{{ o.hint }}</div>
              }
            </nz-option>
          }
        </nz-option-group>
      }
      @if (missing(); as id) {
        <nz-option [nzValue]="id" [nzLabel]="text.unknown" nzDisabled />
      }
    </nz-select>
    @if (chosen(); as c) {
      @if (!c.usable && c.problem) {
        <div class="under danger" role="alert">{{ c.problem }}</div>
      } @else if (c.note) {
        <div class="under warning">{{ c.note }}</div>
      } @else if (c.hint) {
        <div class="under muted">{{ c.hint }}</div>
      }
    } @else if (missing()) {
      <div class="under danger" role="alert">{{ text.missing }}</div>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      nz-select {
        width: 100%;
      }
      .opt {
        display: flex;
        align-items: center;
        gap: 6px;
        min-width: 0;
      }
      .opt .name {
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .opt .route {
        margin-left: auto;
        margin-right: 0;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
      }
      .desc {
        font-size: 12px;
        white-space: normal;
      }
      .under {
        font-size: 12px;
        margin-top: 4px;
      }
      .warning {
        color: var(--bo-you-ink);
      }
    `,
  ],
})
export class ModelSelectComponent implements ControlValueAccessor {
  readonly options = input<readonly ModelChoice[]>([]);
  readonly loading = input(false);
  readonly placeholder = input('');
  readonly allowClear = input(true);
  /** Id of the inner input (for a label's `for`). */
  readonly selectId = input<string | null>(null);

  readonly value = signal<string | null>(null);
  readonly disabled = signal(false);

  readonly text = {
    unknown: $localize`:@@core.modelSelect.unknown:Unknown model profile`,
    missing: $localize`:@@core.modelSelect.missing:This model profile is not available (deleted, disabled or not visible to you).`,
  };

  readonly groups = computed(() => groupModelOptions(this.options()));
  readonly chosen = computed<ModelChoice | null>(() => {
    const id = this.value();
    return (id && this.options().find((o) => o.profileId === id)) || null;
  });
  /** A selected id that is not among the loaded options: kept selected, shown as unknown. */
  readonly missing = computed<string | null>(() => {
    const id = this.value();
    return id && !this.loading() && !this.chosen() ? id : null;
  });

  private onChange: (value: string | null) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  route(o: ModelChoice): string {
    return routeLabel(o.route);
  }

  select(value: string | null | undefined): void {
    const v = value ?? null;
    this.value.set(v);
    this.onChange(v);
  }

  touched(): void {
    this.onTouched();
  }

  writeValue(value: string | null | undefined): void {
    this.value.set(value ?? null);
  }

  registerOnChange(fn: (value: string | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }
}
